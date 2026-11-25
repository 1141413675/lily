package dev.xiaoman.app.dream

import dev.xiaoman.api.ChatMessage
import dev.xiaoman.api.ChatRequest
import dev.xiaoman.api.LlmException
import dev.xiaoman.api.MemoryStore
import dev.xiaoman.api.ModelRole
import dev.xiaoman.api.Note
import dev.xiaoman.api.Observation
import dev.xiaoman.app.settings.SettingsStore
import dev.xiaoman.llm.LlmClientFactory
import dev.xiaoman.memory.BasicMemoryFormat
import dev.xiaoman.memory.Permalinks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * The dream job: once a day, read the last 24h of chat plus relevant memory and ask the chat model
 * **one** question — what to remember, merge, forget, which contradictions to settle, and which
 * general rules to induce. The answer becomes a proposal list ([DreamDay]) the user confirms; low
 * risk items (remember/merge) may be applied automatically when that switch is on.
 *
 * Bounded on purpose: one call, capped conversation and memory text, no retries. A failed night is
 * recorded and skipped, never repeated in a loop.
 */
class DreamEngine(
    private val memory: MemoryStore,
    private val settings: SettingsStore,
    private val llm: LlmClientFactory,
    private val store: DreamStore,
    private val rules: RuleStore,
    private val scope: CoroutineScope,
    /** 昵称为空时的回退名（当前应用名），记忆整理提示里的自称也用它。 */
    private val appName: String = "",
    private val clock: () -> Long = System::currentTimeMillis,
    private val onSchedule: (() -> Unit)? = null,
) {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    /** Bumped after every change so the dream screen reloads. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Mirrors a chat line into the local log; failures are ignored. */
    fun record(role: String, text: String) {
        try {
            store.record(role, text)
        } catch (e: Exception) {
            // Logging must never affect the conversation.
        }
    }

    fun schedule() = onSchedule?.invoke()

    /**
     * Runs tonight's dream when it did not run today. [force] (the screen button) ignores the daily
     * limit, the enabled switch and a failed earlier run.
     */
    fun runIfDue(force: Boolean = false): Job? {
        val config = store.config()
        if (!force) {
            if (!config.enabled) return null
            if (config.lastDreamDate == store.today()) return null
        }
        if (!settings.current.chat.configured) {
            _status.value = "还没接上聊天模型，做梦得先有这个。"
            return null
        }
        return start(force)
    }

    @Synchronized
    private fun start(force: Boolean): Job? {
        if (_running.value) return null
        _running.value = true
        _status.value = "正在读今天的对话…"
        val today = store.today()
        return scope.launch {
            try {
                dream(today)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saveFailed(today, e.message?.takeIf { it.isNotBlank() } ?: "整理失败")
            } finally {
                store.saveConfig(store.config().copy(lastDreamDate = today))
                _running.value = false
                _revision.value += 1
            }
        }
    }

    /** Applies the checked items of [date]; [auto] marks them as undoable auto changes. */
    fun apply(date: String, ids: Set<String>, auto: Boolean = false) {
        if (ids.isEmpty()) return
        scope.launch { applyInternal(date, ids, auto) }
    }

    /** Restores everything the low-risk auto mode changed for [date], as one batch. */
    fun undoAuto(date: String) {
        scope.launch { undoAutoInternal(date) }
    }

    /** 夜里页：这一条不对。只撤回她真正写下的内容，之后不再出现在这一页。 */
    fun rejectItem(date: String, id: String) {
        scope.launch {
            val day = store.day(date) ?: return@launch
            val updated = day.items.map { item ->
                if (item.id == id && item.applied && item.undo != null) {
                    undoItem(item).copy(selected = false)
                } else {
                    item
                }
            }
            store.saveDay(day.copy(items = updated))
            _revision.value += 1
        }
    }

    /** 撤销刚才的「这条不对」：把这一条按原样重新写回去。 */
    fun restoreItem(date: String, id: String) {
        scope.launch {
            val day = store.day(date) ?: return@launch
            val updated = day.items.map { item ->
                if (item.id == id && !item.applied && !item.selected && item.undo == null && item.error == null) {
                    applyItem(item, date, auto = false).copy(selected = true)
                } else {
                    item
                }
            }
            store.saveDay(day.copy(items = updated))
            _revision.value += 1
        }
    }

    /** Restores a note from the recycle bin (the trash entry's absolute path). */
    fun restoreTrash(path: String) {
        scope.launch {
            store.readTrash(path)?.let { (_, note) ->
                memory.write(note)
                store.dropTrash(path)
            }
            _revision.value += 1
        }
    }

    private suspend fun dream(today: String) {
        val conversation = store.recentConversation()
        if (conversation.isBlank()) {
            _status.value = "最近 24 小时没有对话，今晚不做梦。"
            return
        }
        val active = rules.all().filter { it.status == "active" }
        val rejectedKeys = rules.rejectedKeys()
        val rejectedStatements = rules.rejectedStatements()
        val notes = gatherMemories(conversation)
        val request = ChatRequest(
            listOf(
                ChatMessage.system(systemPrompt(rejectedKeys, rejectedStatements)),
                ChatMessage.user(userPrompt(conversation, notes, active)),
            ),
            jsonMode = true,
        )
        _status.value = "正在整理记忆…"
        val client = llm.create(settings.current.chat.toModelConfig(ModelRole.CHAT))
        val response = try {
            client.complete(request)
        } catch (e: LlmException) {
            saveFailed(today, "模型调用失败：${e.message ?: e.javaClass.simpleName}")
            return
        }
        val payload = parsePayload(response.text)
        if (payload == null) {
            saveFailed(today, "模型的返回不是可解析的 JSON，今晚先跳过。")
            return
        }
        val items = buildItems(today, payload, rejectedKeys, rejectedStatements)
        val day = DreamDay(
            date = today,
            createdAt = clock(),
            summary = payload.summary.trim(),
            status = if (items.isEmpty()) DreamTypes.APPLIED else DreamTypes.PROPOSED,
            appliedAt = if (items.isEmpty()) clock() else null,
            calls = 1,
            items = items,
        )
        store.saveDay(day)
        _status.value = if (items.isEmpty()) "今晚没有需要调整的记忆。" else "整理完成：${items.size} 条待确认。"
        if (store.config().autoLowRisk) {
            val lowRisk = items.filter { it.type in DreamTypes.LOW_RISK }.map { it.id }.toSet()
            if (lowRisk.isNotEmpty()) applyInternal(today, lowRisk, auto = true)
        }
    }

    /** Searches memory with the last user lines of [conversation]; capped to [DreamStore.MAX_MEMORY_NOTES]. */
    private suspend fun gatherMemories(conversation: String): List<Note> {
        val queries = conversation.lineSequence()
            .filter { it.contains("用户]") }
            .map { it.substringAfter("] ", "").trim().take(30) }
            .filter { it.isNotEmpty() }
            .toList()
            .takeLast(8)
        val found = LinkedHashMap<String, Note>()
        for (query in queries) {
            if (found.size >= DreamStore.MAX_MEMORY_NOTES) break
            for (hit in runCatching { memory.search(query, 4) }.getOrDefault(emptyList())) {
                if (found.size >= DreamStore.MAX_MEMORY_NOTES) break
                if (hit.permalink in found) continue
                val note = runCatching { memory.read(hit.permalink) }.getOrNull() ?: continue
                if (note.type == RuleStore.TYPE) continue
                found[note.permalink] = note
            }
        }
        return found.values.toList()
    }

    private fun systemPrompt(rejectedKeys: Set<String>, rejectedStatements: Set<String>): String = buildString {
        append("你是").append(settings.current.persona.nameOr(appName)).append("的记忆整理器，负责「做梦」：在空闲时读最近 24 小时的对话和相关记忆，决定哪些记住、哪些合并、哪些忘掉，并归纳长期规律。\n")
        append("只输出一个 JSON 对象，不要解释、不要 Markdown。字段如下：\n")
        append("""{"summary":"当天摘要（150 字内）","remember":[{"title":"","permalink":"","observations":[{"category":"","content":"","tags":[],"context":""}],"reason":""}],""")
        append(""""merge":[{"target":"","title":"","observations":[],"sources":["被合并掉的笔记 permalink"],"reason":""}],""")
        append(""""forget":[{"permalink":"","title":"","reason":""}],""")
        append(""""contradictions":[{"target":"","title":"","remove":["要移除的旧内容原文"],"observations":[],"reason":"判定依据：哪条更新/更具体/来自哪次对话"}],""")
        append(""""rules":[{"key":"英文短键","kind":"preference|habit|address|taboo|schedule","statement":"","confidence":0.0,"evidence":["记忆 permalink 或对话日期"]}]}""")
        append("\n\n规则：\n")
        append("- 只依据给出的对话和记忆，不编造，不把猜测写成事实；没有内容的类别给空数组。\n")
        append("- forget 只用于确实重复、过时或没有信息量的笔记；删除会进回收站。\n")
        append("- 矛盾必须二选一：用 remove 列出旧笔记里需要移除的原文，observations 写新内容，reason 必须说明依据（哪条更新、哪条更具体、来自哪次对话），不允许两条并列保留。\n")
        append("- 归纳规则时，每条必须有 evidence（引用具体记忆或对话日期）和 confidence（0~1）；只归纳能反复影响回答的偏好、习惯、称呼指代、禁忌话题、作息。\n")
        append("- 命名：permalink 用「文件夹/标题」形式，如 people/妈妈；rules 的 key 用短英文。\n")
        if (rejectedKeys.isNotEmpty()) {
            append("- 以下规则已被用户明确推翻，禁止再次提出，也不要换个说法重提：\n")
            rejectedKeys.forEach { append("  · ").append(it).append('\n') }
        }
        if (rejectedStatements.isNotEmpty()) {
            append("- 已被推翻的规则原文（同样不得重提）：\n")
            rejectedStatements.forEach { append("  · ").append(it.take(100)).append('\n') }
        }
    }

    private fun userPrompt(conversation: String, notes: List<Note>, active: List<RuleView>): String = buildString {
        append("## 最近 24 小时的对话\n").append(conversation).append("\n\n")
        append("## 相关记忆\n")
        if (notes.isEmpty()) {
            append("（没有命中的记忆）\n")
        } else {
            var remaining = DreamStore.MAX_MEMORY_CHARS
            for (note in notes) {
                if (remaining <= 0) break
                val text = "### ${note.title} (${note.permalink})\n${note.body.take(800)}\n\n".take(remaining)
                append(text)
                remaining -= text.length
            }
        }
        append("\n## 已有规则（不要重复提出，除非有新证据可以升级确信度）\n")
        if (active.isEmpty()) append("（还没有规则）\n") else active.forEach {
            append("- [").append(it.key).append("] ").append(it.statement).append("（确信 ").append(it.confidence).append("）\n")
        }
    }

    private suspend fun buildItems(
        date: String,
        payload: Payload,
        rejectedKeys: Set<String>,
        rejectedStatements: Set<String>,
    ): List<DreamItem> {
        val items = mutableListOf<DreamItem>()
        var index = 0
        fun nextId(type: String) = "$date-$type-${index++}"

        payload.remember.forEach { dto ->
            val permalink = normalize(dto.permalink.ifBlank { Permalinks.fromTitle(dto.title) }) ?: return@forEach
            val observations = dto.observations.filter { it.content.isNotBlank() }
            if (observations.isEmpty()) return@forEach
            items += DreamItem(
                id = nextId(DreamTypes.REMEMBER),
                type = DreamTypes.REMEMBER,
                title = dto.title.ifBlank { permalink.substringAfterLast('/') },
                permalink = permalink,
                observations = observations,
                reason = dto.reason,
            )
        }

        payload.merge.forEach { dto ->
            val target = normalize(dto.target.ifBlank { dto.title }) ?: return@forEach
            if (!exists(target)) return@forEach
            val sources = dto.sources.mapNotNull(::normalize).filter { it != target && exists(it) }
            items += DreamItem(
                id = nextId(DreamTypes.MERGE),
                type = DreamTypes.MERGE,
                title = dto.title.ifBlank { target.substringAfterLast('/') },
                permalink = target,
                target = target,
                observations = dto.observations.filter { it.content.isNotBlank() },
                sources = sources,
                reason = dto.reason,
            )
        }

        payload.forget.forEach { dto ->
            val permalink = normalize(dto.permalink.ifBlank { dto.title }) ?: return@forEach
            val note = runCatching { memory.read(permalink) }.getOrNull() ?: return@forEach
            items += DreamItem(
                id = nextId(DreamTypes.FORGET),
                type = DreamTypes.FORGET,
                title = note.title,
                permalink = note.permalink,
                reason = dto.reason,
            )
        }

        payload.contradictions.forEach { dto ->
            val target = normalize(dto.target.ifBlank { dto.title }) ?: return@forEach
            val note = runCatching { memory.read(target) }.getOrNull() ?: return@forEach
            val added = dto.observations.filter { it.content.isNotBlank() }
            if (added.isEmpty()) return@forEach
            items += DreamItem(
                id = nextId(DreamTypes.CONTRADICTION),
                type = DreamTypes.CONTRADICTION,
                title = note.title,
                permalink = note.permalink,
                target = note.permalink,
                remove = dto.remove,
                observations = added,
                reason = dto.reason,
            )
        }

        payload.rules.forEach { dto ->
            val key = dto.key.trim().ifBlank { Permalinks.fromTitle(dto.statement) }
            val statement = dto.statement.trim()
            if (statement.isEmpty() || dto.evidence.isEmpty()) return@forEach
            if (key in rejectedKeys || normalizeText(statement) in rejectedStatements) return@forEach
            items += DreamItem(
                id = nextId(DreamTypes.RULE),
                type = DreamTypes.RULE,
                title = statement.take(30),
                permalink = "${RuleStore.FOLDER}/${Permalinks.fromTitle(key)}",
                rule = DreamRule(
                    key = key,
                    kind = dto.kind.ifBlank { "preference" },
                    statement = statement,
                    confidence = dto.confidence.coerceIn(0.0, 1.0),
                    evidence = dto.evidence,
                ),
                sources = dto.evidence,
                reason = "由 ${dto.evidence.size} 条证据归纳",
            )
        }
        return items
    }

    private suspend fun applyInternal(date: String, ids: Set<String>, auto: Boolean) {
        val day = store.day(date) ?: return
        val updated = day.items.map { item ->
            if (item.id in ids && !item.applied) applyItem(item, date, auto) else item
        }
        val remaining = updated.count { !it.applied }
        store.saveDay(
            day.copy(
                items = updated,
                status = if (remaining == 0) DreamTypes.APPLIED else DreamTypes.PROPOSED,
                appliedAt = if (remaining == 0) clock() else day.appliedAt,
            ),
        )
        _status.value = if (remaining == 0) "已全部落地。" else "还有 $remaining 条待确认。"
        _revision.value += 1
    }

    private suspend fun applyItem(item: DreamItem, date: String, auto: Boolean): DreamItem = try {
        when (item.type) {
            DreamTypes.REMEMBER -> applyRemember(item, auto)
            DreamTypes.MERGE -> applyMerge(item, date, auto)
            DreamTypes.FORGET -> applyForget(item, date)
            DreamTypes.CONTRADICTION -> applyContradiction(item, date)
            DreamTypes.RULE -> applyRule(item, date)
            else -> item.copy(error = "未知类型")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        item.copy(error = e.message?.takeIf { it.isNotBlank() } ?: "落地失败")
    }

    private suspend fun applyRemember(item: DreamItem, auto: Boolean): DreamItem {
        val permalink = normalize(item.permalink.ifBlank { item.title }) ?: error("缺少笔记路径")
        val existing = memory.read(permalink)
        val created = existing == null
        val added = mutableListOf<DreamObservation>()
        if (created) {
            memory.write(
                Note(
                    title = item.title.ifBlank { permalink.substringAfterLast('/') },
                    permalink = permalink,
                    observations = item.observations.map { it.toObservation() },
                    updatedAtMillis = clock(),
                ),
            )
            added += item.observations
        } else {
            val seen = existing!!.observations.map { normalizeText(it.content) }.toMutableSet()
            item.observations.forEach { obs ->
                if (seen.add(normalizeText(obs.content))) {
                    memory.appendObservation(permalink, obs.toObservation())
                    added += obs
                }
            }
        }
        return item.copy(
            permalink = permalink,
            applied = true,
            auto = auto,
            undo = DreamUndo(added = added, createdNote = created),
        )
    }

    private suspend fun applyMerge(item: DreamItem, date: String, auto: Boolean): DreamItem {
        val target = normalize(item.target.ifBlank { item.permalink }) ?: error("缺少合并目标")
        val existing = memory.read(target)
        val created = existing == null
        val added = mutableListOf<DreamObservation>()
        if (created) {
            memory.write(
                Note(
                    title = item.title.ifBlank { target.substringAfterLast('/') },
                    permalink = target,
                    observations = item.observations.map { it.toObservation() },
                    updatedAtMillis = clock(),
                ),
            )
            added += item.observations
        } else {
            val seen = existing!!.observations.map { normalizeText(it.content) }.toMutableSet()
            item.observations.forEach { obs ->
                if (seen.add(normalizeText(obs.content))) {
                    memory.appendObservation(target, obs.toObservation())
                    added += obs
                }
            }
        }
        val trashFiles = mutableListOf<String>()
        item.sources.mapNotNull(::normalize).filter { it != target }.forEach { source ->
            val note = memory.read(source) ?: return@forEach
            store.trash(note, "合并到 $target", date)?.let { trashFiles += it }
            memory.delete(note.permalink)
        }
        return item.copy(
            permalink = target,
            target = target,
            applied = true,
            auto = auto,
            undo = DreamUndo(added = added, createdNote = created, trashFiles = trashFiles),
        )
    }

    private suspend fun applyForget(item: DreamItem, date: String): DreamItem {
        val permalink = normalize(item.permalink.ifBlank { item.title }) ?: error("缺少笔记路径")
        val note = memory.read(permalink) ?: error("笔记不存在")
        store.trash(note, item.reason.ifBlank { "重复或过时" }, date) ?: error("无法写入回收站")
        memory.delete(note.permalink)
        return item.copy(permalink = note.permalink, applied = true)
    }

    private suspend fun applyContradiction(item: DreamItem, date: String): DreamItem {
        val target = normalize(item.target.ifBlank { item.permalink }) ?: error("缺少笔记路径")
        val note = memory.read(target) ?: error("笔记不存在")
        val backup = store.backupHistory(note, date)
        val newBody = removeObservationLines(note.body, item.remove)
        val kept = note.observations.filterNot { observationMatches(it, item.remove) }
        val newLines = item.observations.joinToString("；") { it.content }.take(80)
        val oldLines = (item.remove.ifEmpty { listOf(note.observations.joinToString("；") { it.content }) })
            .joinToString("；").take(80)
        val history = buildString {
            if (!newBody.contains(HISTORY_HEADER)) {
                append(newBody.trimEnd()).append("\n\n").append(HISTORY_HEADER).append('\n')
            } else {
                append(newBody.trimEnd()).append('\n')
            }
            append("- ").append(LocalDate.now()).append(" 旧「").append(oldLines).append("」→ 新「").append(newLines)
            append("」；依据：").append(item.reason.ifBlank { "新记忆替代旧内容" })
            backup?.let { append("（旧版备份 ").append(it).append("）") }
            append('\n')
        }
        memory.write(note.copy(body = history, observations = kept, updatedAtMillis = clock()))
        val seen = kept.map { normalizeText(it.content) }.toMutableSet()
        item.observations.forEach { obs ->
            if (seen.add(normalizeText(obs.content))) memory.appendObservation(note.permalink, obs.toObservation())
        }
        return item.copy(permalink = note.permalink, target = note.permalink, applied = true)
    }

    private suspend fun applyRule(item: DreamItem, date: String): DreamItem {
        val rule = item.rule ?: error("缺少规则内容")
        rules.save(rule, item.sources, date)
        return item.copy(permalink = "${RuleStore.FOLDER}/${Permalinks.fromTitle(rule.key)}", applied = true)
    }

    private suspend fun undoAutoInternal(date: String) {
        val day = store.day(date) ?: return
        val updated = day.items.map { item ->
            if (item.auto && item.applied) undoItem(item) else item
        }
        val remaining = updated.count { !it.applied }
        store.saveDay(
            day.copy(
                items = updated,
                status = if (remaining == 0) DreamTypes.APPLIED else DreamTypes.PROPOSED,
            ),
        )
        _status.value = "已撤回自动变更。"
        _revision.value += 1
    }

    private suspend fun undoItem(item: DreamItem): DreamItem {
        val undo = item.undo ?: return item
        val permalink = item.permalink.ifBlank { item.target }
        if (permalink.isNotBlank()) {
            val note = memory.read(permalink)
            if (note != null) {
                val body = removeObservationLines(note.body, undo.added.map { it.content })
                val kept = note.observations.filterNot { observationMatches(it, undo.added.map { it.content }) }
                val createdOnly = undo.createdNote && kept.isEmpty() && body.lineSequence().none { it.trim().startsWith("- [") }
                if (createdOnly) memory.delete(note.permalink)
                else memory.write(note.copy(body = body, observations = kept, updatedAtMillis = clock()))
            }
        }
        undo.trashFiles.forEach { path ->
            store.readTrash(path)?.let { (_, note) -> memory.write(note) }
            store.dropTrash(path)
        }
        return item.copy(applied = false, auto = false, undo = null, error = null)
    }

    private fun removeObservationLines(body: String, contents: List<String>): String {
        if (contents.isEmpty()) return body
        return body.lines().filterNot { line ->
            val observation = BasicMemoryFormat.parseObservation(line) ?: return@filterNot false
            observationMatches(observation, contents)
        }.joinToString("\n")
    }

    private fun observationMatches(observation: Observation, contents: List<String>): Boolean {
        val content = normalizeText(observation.content)
        return contents.any { normalizeText(it) == content }
    }

    private fun DreamObservation.toObservation() = Observation(category.ifBlank { "note" }, content, tags, context)

    private suspend fun exists(permalink: String): Boolean = runCatching { memory.read(permalink) }.getOrNull() != null

    private fun normalize(permalink: String): String? {
        if (permalink.isBlank()) return null
        return runCatching { Permalinks.normalize(permalink) }.getOrNull()
    }

    private fun normalizeText(text: String) = text.replace(Regex("\\s+"), "").lowercase()

    private fun saveFailed(date: String, message: String) {
        val day = DreamDay(date, clock(), status = DreamTypes.FAILED, error = message, calls = 1)
        runCatching { store.saveDay(day) }
        _status.value = "今晚的整理没有完成：$message"
    }

    private fun parsePayload(text: String): Payload? {
        val cleaned = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.decodeFromString<Payload>(cleaned.substring(start, end + 1)) }.getOrNull()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class Payload(
        val summary: String = "",
        val remember: List<RememberDto> = emptyList(),
        val merge: List<MergeDto> = emptyList(),
        val forget: List<ForgetDto> = emptyList(),
        val contradictions: List<ContradictionDto> = emptyList(),
        val rules: List<RuleDto> = emptyList(),
    )

    @Serializable
    private data class RememberDto(
        val title: String = "",
        val permalink: String = "",
        val observations: List<DreamObservation> = emptyList(),
        val reason: String = "",
    )

    @Serializable
    private data class MergeDto(
        val target: String = "",
        val title: String = "",
        val observations: List<DreamObservation> = emptyList(),
        val sources: List<String> = emptyList(),
        val reason: String = "",
    )

    @Serializable
    private data class ForgetDto(val permalink: String = "", val title: String = "", val reason: String = "")

    @Serializable
    private data class ContradictionDto(
        val target: String = "",
        val title: String = "",
        val remove: List<String> = emptyList(),
        val observations: List<DreamObservation> = emptyList(),
        val reason: String = "",
    )

    @Serializable
    private data class RuleDto(
        val key: String = "",
        val kind: String = "preference",
        val statement: String = "",
        val confidence: Double = 0.7,
        val evidence: List<String> = emptyList(),
    )

    companion object {
        private const val HISTORY_HEADER = "## 版本更替"
    }
}
