package cn.jiaoban.parse

/**
 * 从一句话里认「谁交代的」。
 *
 * 中文人名识别在手机上跑不了模型（GitHub 上全是 Python 深度学习），所以用三条规则：
 *   1. 你最近记过的人名，句子里出现了就直接用（最准）
 *   2. 「说 / 让 / 要 / 叫 / 催 / 要求 / 嘱咐 / 交代」前面的名字
 *   3. 没有姓氏的称呼：老板、领导、客户这种
 * 认错了点一下就能改，所以宁可偶尔认错，也别让用户每次都手填。
 */
object PersonParser {

    private val MARKERS = listOf("要求", "嘱咐", "交代", "通知", "说", "让", "要", "叫", "催", "问")

    /** 姓氏后面常见的称呼。 */
    private val TITLES = listOf(
        "经理", "总监", "主任", "主管", "组长", "部长", "老板", "老师", "总", "姐", "哥", "工",
    )

    /** 不带姓氏的称呼。 */
    private val STANDALONE = listOf(
        "老板", "领导", "老大", "客户", "甲方", "人事", "财务", "前台", "同事", "老婆", "老公", "妈", "爸",
    )

    /** 常见姓氏，用来判断「这几个字像不像人名」。 */
    private const val SURNAMES =
        "赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜戚谢邹喻柏水窦章云苏潘葛奚范彭郎鲁韦昌马苗凤花方俞任袁柳唐罗薛伍余米贝姚孟高夏蔡田樊胡凌霍虞万支柯昝管卢莫房裘缪干解应宗丁宣邓郁单杭洪包诸左石崔吉龚程邢裴陆荣翁荀羊甄封芮储靳汲邴糜松井段富巫乌焦巴弓牧隗山谷车侯宓蓬全郗班仰秋仲伊宫宁仇栾暴甘厉戎祖武符刘景詹束龙叶幸司韶郜黎蓟薄印宿白怀蒲邰从鄂索咸籍赖卓蔺屠蒙池乔阴胥能苍双闻莘党翟谭贡劳逄姬申扶堵冉宰郦雍桑桂濮牛寿通边扈燕冀郏浦尚农温别庄晏柴瞿阎充慕连茹习宦艾鱼容向古易慎戈廖庾终暨居衡步都耿满弘匡国文寇广禄阙东欧殳沃利蔚越夔隆师巩厍聂晁勾敖融冷訾辛阚那简饶空曾毋沙乜养鞠须丰巢关蒯相查后荆红游竺权逯盖益桓公"

    /** known 传你最近记过的人名，优先精确匹配。 */
    fun parse(text: String, known: List<String> = emptyList()): String {
        val clean = text.trim()
        if (clean.isEmpty()) return ""

        known.firstOrNull { it.trim().length >= 2 && clean.contains(it.trim()) }?.let { return it.trim() }

        for (marker in MARKERS) {
            val index = clean.indexOf(marker)
            if (index <= 0) continue
            tailName(clean.substring(0, index))?.let { return it }
        }

        // 没有姓氏的称呼只认出现在句首的，免得「把周报发给客户」里的客户被当成交代人
        STANDALONE.firstOrNull { clean.indexOf(it) in 0..3 }?.let { return it }
        return ""
    }

    /** 从标记词前面那段文字里，切出最像人名的一截。 */
    private fun tailName(prefix: String): String? {
        val tail = prefix.takeLast(4)
        if (tail.isEmpty()) return null

        for (title in TITLES) {
            if (!tail.endsWith(title)) continue
            val before = tail.dropLast(title.length)
            if (before.isNotEmpty() && before.last() in SURNAMES) return "" + before.last() + title
        }

        for (length in intArrayOf(3, 2)) {
            if (tail.length < length) continue
            val candidate = tail.takeLast(length)
            if (candidate.first() in SURNAMES) return candidate
        }
        return null
    }
}
