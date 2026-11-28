package dev.lily.agent

import dev.lily.api.Action
import dev.lily.api.Point
import dev.lily.api.ScreenState
import dev.lily.api.UiNode

/**
 * The hard refusal layer. It runs before the model (on the user's message and on a phone-task goal)
 * and before every device action, so no prompt, skill or coordinate can reach a payment, password
 * or verification-code flow.
 *
 * Everything here is a pure function so the hit list and the false-positive corpus are unit tested.
 */
object PaymentSafety {
    const val REFUSAL = "这个得你自己来。付款、密码、验证码这类操作我不会做。"

    data class Hit(val rule: String, val matched: String)

    private data class Rule(val name: String, val regex: Regex)

    /** High-signal phrases: a hit is refused, never confirmed. */
    private val instructionRules = listOf(
        Rule("payment", Regex("付款|支付|收付款|付款码|收银台|确认付款|立即支付|扫码付|免密|指纹支付|面容支付|刷脸支付|支付密码")),
        Rule("transfer", Regex("转账|汇款|打款|打钱|红包|提现|还款|还信用卡")),
        Rule("recharge", Regex("充值|话费充值|流量充值")),
        Rule("verification-code", Regex("验证码|短信码|动态码|校验码")),
        Rule("password", Regex("支付密码|锁屏密码|开机密码|输入密码|密码输入")),
        Rule("bank", Regex("银行卡|信用卡|银行|支付宝|微信支付|云闪付")),
        Rule("trade", Regex("(证券|股票|基金|期货).{0,6}(买入|卖出|委托|申购|赎回)|(买入|卖出|申购|赎回).{0,6}(股|手|证券|股票|基金|期货)")),
        Rule("loan", Regex("贷款|分期付款|花呗|借呗|白条|微粒贷")),
    )

    /** Bank, payment and securities apps: no device action inside them at all. */
    private val protectedPackages = Regex(
        "(^|\\.)(bank|ebank|banking)(\\.|$)|icbc|ccb\\.|abchina|boc\\.|cmbchina|bankcomm|spdb|ecitic|citic|" +
            "pingan|unionpay|alipay|hexin|eastmoney|securities|stock|gtja|htsc|citics|guosen|guotai|dfzq|lphtsccft",
        RegexOption.IGNORE_CASE,
    )

    /** Page text that only appears on a payment, password or transfer surface. */
    private val surfaceText = Regex(
        "支付密码|付款码|收银台|确认付款|立即支付|转账金额|收款方|收款账户|请输入密码|免密支付|指纹支付|" +
            "面容支付|银行卡号|安全码|CVV|验证码",
    )

    private val paymentUri = Regex(
        "alipays?://|weixin://wxpay|unionpay://|https?://[^\\s]*(alipay|tenpay|bank)",
        RegexOption.IGNORE_CASE,
    )

    /** Null when [text] is safe; otherwise the first rule it hits. */
    fun instruction(text: String?): Hit? {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return null
        for (rule in instructionRules) {
            rule.regex.find(value)?.let { match -> return Hit(rule.name, match.value) }
        }
        return null
    }

    /** Null when [action] may run on [screen]; otherwise the hard-refusal hit. */
    fun action(action: Action, screen: ScreenState?): Hit? {
        when (action) {
            is Action.Back, is Action.Home, is Action.Wait, is Action.AskUser, is Action.Finish -> return null
            is Action.LaunchApp -> if (protectedPackage(action.packageName)) return Hit("protected-app", action.packageName)
            is Action.OpenDeeplink -> {
                action.packageName?.takeIf(::protectedPackage)?.let { return Hit("protected-app", it) }
                paymentUri.find(action.uri)?.let { return Hit("payment-link", it.value) }
            }
            else -> screen?.packageName?.takeIf(::protectedPackage)?.let { return Hit("protected-app", it) }
        }
        val node = nodeFor(screen, action)
        if (node?.password == true) return Hit("password-field", node.text ?: "密码输入框")

        val candidates = buildList {
            action.target?.let(::add)
            (action as? Action.TypeText)?.text?.let(::add)
            node?.text?.let(::add)
            node?.description?.let(::add)
        }
        for (text in candidates) {
            instruction(text)?.let { return Hit("action-${it.rule}", it.matched) }
        }
        if (screen != null && surface(screen)) {
            when (action) {
                is Action.Tap, is Action.LongPress, is Action.TypeText, is Action.Swipe ->
                    return Hit("payment-screen", screen.packageName ?: "支付页面")
                else -> Unit
            }
        }
        return null
    }

    fun protectedPackage(packageName: String?): Boolean {
        val value = packageName?.trim()?.lowercase().orEmpty()
        if (value.isEmpty()) return false
        return protectedPackages.containsMatchIn(value)
    }

    fun surface(screen: ScreenState): Boolean {
        if (protectedPackage(screen.packageName)) return true
        return screen.nodes.any { node ->
            surfaceText.containsMatchIn(node.text.orEmpty()) || surfaceText.containsMatchIn(node.description.orEmpty())
        }
    }

    private fun nodeFor(screen: ScreenState?, action: Action): UiNode? {
        if (screen == null) return null
        return when (action) {
            is Action.Tap -> byId(screen, action.nodeId) ?: action.point?.let { atPoint(screen, it) }
            is Action.LongPress -> byId(screen, action.nodeId) ?: action.point?.let { atPoint(screen, it) }
            is Action.TypeText -> if (action.nodeId != null) byId(screen, action.nodeId) else focusedEditable(screen)
            else -> null
        }
    }

    private fun byId(screen: ScreenState, id: Int?): UiNode? = id?.let { wanted -> screen.nodes.firstOrNull { it.id == wanted } }

    private fun focusedEditable(screen: ScreenState): UiNode? = screen.nodes.firstOrNull { it.focused && it.editable }

    private fun atPoint(screen: ScreenState, point: Point): UiNode? {
        var best: UiNode? = null
        var bestArea = Long.MAX_VALUE
        for (node in screen.nodes) {
            val b = node.bounds
            if (point.x !in b.left..b.right || point.y !in b.top..b.bottom) continue
            val area = (b.right - b.left).toLong() * (b.bottom - b.top).toLong()
            if (area <= bestArea) {
                best = node
                bestArea = area
            }
        }
        return best
    }
}
