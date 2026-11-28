package dev.lily.app.profile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * 用户档案存储：`<files>/profile/me.json`，与她的人设（prefs）和头像（portrait 目录）分开。
 * 写入同步落盘，页面与注入都读同一个 [StateFlow]；初始为空，不迁移、不改任何旧数据。
 */
class MyProfileStore(private val file: File) {

    private val _state = MutableStateFlow(load())
    val state: StateFlow<MyProfile> = _state.asStateFlow()

    val current: MyProfile get() = _state.value

    @Synchronized
    fun save(profile: MyProfile) {
        _state.value = profile
        persist(profile)
    }

    @Synchronized
    fun update(transform: (MyProfile) -> MyProfile) = save(transform(_state.value))

    private fun load(): MyProfile = runCatching {
        if (!file.isFile) return MyProfile()
        val json = JSONObject(file.readText())
        MyProfile(
            nickname = json.optString("nickname"),
            gender = runCatching { ProfileGender.valueOf(json.optString("gender")) }
                .getOrDefault(ProfileGender.UNSET),
            birthday = json.optString("birthday"),
            fixedAge = if (json.has("fixedAge")) json.optInt("fixedAge") else null,
            hometown = json.optString("hometown"),
            city = json.optString("city"),
            education = json.optString("education"),
            pets = json.optString("pets"),
            relationship = json.optString("relationship"),
            appearance = json.optString("appearance"),
            personality = json.optString("personality"),
            tone = json.optString("tone"),
            lifestyle = json.optString("lifestyle"),
            background = json.optString("background"),
        )
    }.getOrDefault(MyProfile())

    private fun persist(profile: MyProfile) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                JSONObject().apply {
                    put("nickname", profile.nickname)
                    put("gender", profile.gender.name)
                    put("birthday", profile.birthday)
                    profile.fixedAge?.let { put("fixedAge", it) }
                    put("hometown", profile.hometown)
                    put("city", profile.city)
                    put("education", profile.education)
                    put("pets", profile.pets)
                    put("relationship", profile.relationship)
                    put("appearance", profile.appearance)
                    put("personality", profile.personality)
                    put("tone", profile.tone)
                    put("lifestyle", profile.lifestyle)
                    put("background", profile.background)
                }.toString(),
            )
        }
    }
}
