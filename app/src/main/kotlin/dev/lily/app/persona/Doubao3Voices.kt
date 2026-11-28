package dev.lily.app.persona

/**
 * 豆包语音合成大模型 3.0 的预置音色。
 *
 * 音色 id 与 2.0 完全不同（3.0 是 `multi_xxx_v3_uranus_bigtts`），
 * 2.0 的音色在 3.0 上用不了，两套清单别混。
 *
 * 数据来自官方「音色列表-TTS3.0」，2026-11-28 抓取：
 * https://docs.volcengine.com/docs/6561/2851033
 */
object Doubao3Voices {

    val FEMALE = listOf(
        VoiceOption("multi_female_tuanzi_v3_uranus_bigtts", "团子 3.0", "豆包 3.0 · 中文", Gender.FEMALE),
        VoiceOption("multi_female_zhixingnv_v3_uranus_bigtts", "知性女声 3.0", "豆包 3.0 · 中文", Gender.FEMALE),
        VoiceOption("multi_female_xinlingjitang_v3_uranus_bigtts", "心灵鸡汤 3.0", "豆包 3.0 · 中文", Gender.FEMALE),
        VoiceOption("multi_female_wenrouxiaoya_v3_uranus_bigtts", "温柔小雅 3.0", "豆包 3.0 · 中文", Gender.FEMALE),
        VoiceOption("multi_female_wenroushunv_v3_uranus_bigtts", "温柔淑女 3.0", "豆包 3.0 · 中文", Gender.FEMALE),
        VoiceOption("multi_female_kefunvsheng_v3_uranus_bigtts", "暖阳女声 3.0", "豆包 3.0 · 中文", Gender.FEMALE),
        VoiceOption("multi_female_huoliyingxiao_nv_v3_uranus_bigtts", "活力小满 3.0", "豆包 3.0 · 中文", Gender.FEMALE),
        VoiceOption("multi_female_wenroucuishou_lingling_v3_uranus_bigtts", "外呼凌凌 3.0", "豆包 3.0 · 中文", Gender.FEMALE),
        VoiceOption("multi_female_jietingkefu_wanjun_v3_uranus_bigtts", "客服婉君 3.0", "豆包 3.0 · 中文", Gender.FEMALE),
        VoiceOption("multi_female_yingxiaokefu_xiaonan_v3_uranus_bigtts", "营销小楠 3.0", "豆包 3.0 · 中文", Gender.FEMALE),
        VoiceOption("multi_female_sarah_v3_uranus_bigtts", "Sarah 3.0", "豆包 3.0 · 英语（美式口音）", Gender.FEMALE),
        VoiceOption("multi_female_wenrouzhishijieshuonv_v3_uranus_bigtts", "Megan 3.0", "豆包 3.0 · 英语（美式口音）", Gender.FEMALE),
        VoiceOption("multi_female_scarlet_p1_v3_uranus_bigtts", "Scarlet 3.0", "豆包 3.0 · 英语（美式口音）", Gender.FEMALE),
        VoiceOption("multi_female_lana_del_rey_parky_s_p1_v3_uranus_bigtts", "Ivy 3.0", "豆包 3.0 · 英语（美式口音）", Gender.FEMALE),
        VoiceOption("multi_female_jenny_v3_uranus_bigtts", "Jenny 3.0", "豆包 3.0 · 英语（美式口音）", Gender.FEMALE),
        VoiceOption("multi_female_yingyujiaoxue_v3_uranus_bigtts", "Tina老师 3.0", "豆包 3.0 · 中文", Gender.FEMALE),
        VoiceOption("multi_female_zendaya_p1_v3_uranus_bigtts", "Zendaya 3.0", "豆包 3.0 · 英语（美式口音）", Gender.FEMALE),
        VoiceOption("multi_female_myra_cmb_v3_uranus_bigtts", "Sunny 3.0", "豆包 3.0 · 英语（美式口音）", Gender.FEMALE),
        VoiceOption("multi_female_myra_v3_uranus_bigtts", "Myra 3.0", "豆包 3.0 · 英语（美式口音）", Gender.FEMALE),
        VoiceOption("multi_female_mel_v3_uranus_bigtts", "Mel 3.0", "豆包 3.0 · 英语（美式口音）", Gender.FEMALE),
        VoiceOption("multi_female_xiaoxue_v3_uranus_bigtts", "儿童绘本 3.0", "豆包 3.0 · 中文", Gender.FEMALE),
        VoiceOption("multi_female_shaoergushi_v3_uranus_bigtts", "少儿故事 3.0", "豆包 3.0 · 中文", Gender.FEMALE),
        VoiceOption("multi_female_tvbnv_v3_uranus_bigtts", "TVB女声 3.0", "豆包 3.0 · 中文", Gender.FEMALE),
        VoiceOption("multi_female_qiaopinv_v3_uranus_bigtts", "俏皮女声 3.0", "豆包 3.0 · 中文", Gender.FEMALE),
        VoiceOption("multi_female_peiqi_v3_uranus_bigtts", "佩奇猪 3.0", "豆包 3.0 · 中文", Gender.FEMALE),
        VoiceOption("multi_female_mizai_v3_uranus_bigtts", "黑猫侦探社咪仔 3.0", "豆包 3.0 · 中文", Gender.FEMALE),
        VoiceOption("multi_female_liuchangnv_v3_uranus_bigtts", "流畅女声 3.0", "豆包 3.0 · 中文", Gender.FEMALE),
        VoiceOption("multi_female_chunribu_v3_uranus_bigtts", "春日部姐姐 3.0", "豆包 3.0 · 中文", Gender.FEMALE),
        VoiceOption("multi_female_xiaohe_taiwan_v3_uranus_bigtts", "小何 3.0", "豆包 3.0 · 中文", Gender.FEMALE),
        VoiceOption("multi_female_stokie_v3_uranus_bigtts", "Stokie 3.0", "豆包 3.0 · 英语（英式口音）", Gender.FEMALE),
        VoiceOption("multi_female_lisa_v3_uranus_bigtts", "Lisa 3.0", "豆包 3.0 · 英语（美式口音）", Gender.FEMALE),
        VoiceOption("multi_female_lisaamused_v3_uranus_bigtts", "Grace 3.0", "豆包 3.0 · 英语（美式口音）", Gender.FEMALE),
        VoiceOption("multi_female_dacey_v3_uranus_bigtts", "Dacey 3.0", "豆包 3.0 · 英语（美式口音）", Gender.FEMALE),
        VoiceOption("multi_female_emosarah_v3_uranus_bigtts", "Naomi 3.0", "豆包 3.0 · 英语（美式口音）", Gender.FEMALE),
        VoiceOption("multi_female_changshadialectfemalevoicedb_1_v3_uranus_bigtts", "长沙小湘 3.0", "豆包 3.0 · 长沙话", Gender.FEMALE),
        VoiceOption("multi_female_henandialectfemalevoicedb_1_v3_uranus_bigtts", "豫州小妮 3.0", "豆包 3.0 · 河南话", Gender.FEMALE),
        VoiceOption("multi_female_sichuandialectfemalevoicedb_3_v3_uranus_bigtts", "安逸川妹 3.0", "豆包 3.0 · 四川话", Gender.FEMALE),
        VoiceOption("multi_female_cantonesefemalevoicedb_4_v3_uranus_bigtts", "粤语小洁 3.0", "豆包 3.0 · 粤语（香港口音）", Gender.FEMALE),
        VoiceOption("multi_female_sharron_v3_uranus_bigtts", "Sharron 3.0", "豆包 3.0 · 英语（美式口音）", Gender.FEMALE),
        VoiceOption("multi_female_rachel_p1_v3_uranus_bigtts", "Rachel 3.0", "豆包 3.0 · 英语（美式口音）", Gender.FEMALE),
    )

    val MALE = listOf(
        VoiceOption("multi_male_taocheng_v3_uranus_bigtts", "小天 3.0", "豆包 3.0 · 中文", Gender.MALE),
        VoiceOption("multi_male_m191_v3_uranus_bigtts", "云舟 3.0", "豆包 3.0 · 中文", Gender.MALE),
        VoiceOption("multi_male_jamie_v3_uranus_bigtts", "Jamie 3.0", "豆包 3.0 · 英语（美式口音）", Gender.MALE),
        VoiceOption("multi_male_youyoujunzi_v3_uranus_bigtts", "悠悠君子 3.0", "豆包 3.0 · 中文", Gender.MALE),
        VoiceOption("multi_male_yangguangqingnian_v3_uranus_bigtts", "阳光青年 3.0", "豆包 3.0 · 中文", Gender.MALE),
        VoiceOption("multi_male_michael_kevin_v3_uranus_bigtts", "Michael_Kevin 3.0", "豆包 3.0 · 英语（美式口音）", Gender.MALE),
        VoiceOption("multi_male_michael_v3_uranus_bigtts", "Hank 3.0", "豆包 3.0 · 英语（美式口音）", Gender.MALE),
        VoiceOption("multi_male_kevin_v3_uranus_bigtts", "Kevin 3.0", "豆包 3.0 · 英语（美式口音）", Gender.MALE),
        VoiceOption("multi_male_josh_coery_v3_uranus_bigtts", "Josiah 3.0", "豆包 3.0 · 英语（美式口音）", Gender.MALE),
        VoiceOption("multi_male_xuanyijieshuo_v3_uranus_bigtts", "悬疑解说 3.0", "豆包 3.0 · 中文", Gender.MALE),
        VoiceOption("multi_male_shenyeboke_v3_uranus_bigtts", "深夜播客 3.0", "豆包 3.0 · 中文", Gender.MALE),
        VoiceOption("multi_male_qingcang_v3_uranus_bigtts", "擎苍 3.0", "豆包 3.0 · 中文", Gender.MALE),
        VoiceOption("multi_male_m208_v3_uranus_bigtts", "Levi 3.0", "豆包 3.0 · 英语（美式口音）", Gender.MALE),
        VoiceOption("multi_male_audiobooknick_v3_uranus_bigtts", "Peter 3.0", "豆包 3.0 · 英语（美式口音）", Gender.MALE),
        VoiceOption("multi_male_mpho_v3_uranus_bigtts", "Owen 3.0", "豆包 3.0 · 英语（美式口音）", Gender.MALE),
        VoiceOption("multi_male_benukaudiobook_v3_uranus_bigtts", "Tobias 3.0", "豆包 3.0 · 英语（英式口音）", Gender.MALE),
        VoiceOption("multi_male_marcus_v3_uranus_bigtts", "Marcus 3.0", "豆包 3.0 · 英语（美式口音）", Gender.MALE),
        VoiceOption("multi_male_yizhipiannan_v3_uranus_bigtts", "译制片男 3.0", "豆包 3.0 · 中文", Gender.MALE),
        VoiceOption("multi_male_sunwukong_v3_uranus_bigtts", "猴哥 3.0", "豆包 3.0 · 中文", Gender.MALE),
        VoiceOption("multi_male_ruyayichen_v3_uranus_bigtts", "儒雅逸辰 3.0", "豆包 3.0 · 中文", Gender.MALE),
        VoiceOption("multi_male_liangsangmengzai_v3_uranus_bigtts", "亮嗓萌仔 3.0", "豆包 3.0 · 中文", Gender.MALE),
        VoiceOption("multi_male_lanyinmianbao_v3_uranus_bigtts", "懒音绵宝 3.0", "豆包 3.0 · 中文", Gender.MALE),
        VoiceOption("multi_male_kailangdidi_v3_uranus_bigtts", "开朗弟弟 3.0", "豆包 3.0 · 中文", Gender.MALE),
        VoiceOption("multi_male_tim_v3_uranus_bigtts", "Tim 3.0", "豆包 3.0 · 英语（美式口音）", Gender.MALE),
        VoiceOption("multi_male_emompho_v3_uranus_bigtts", "Mpho 3.0", "豆包 3.0 · 英语（美式口音）", Gender.MALE),
        VoiceOption("multi_male_cantonesemalevoicedb_3_v3_uranus_bigtts", "粤语小滨 3.0", "豆包 3.0 · 粤语（香港口音）", Gender.MALE),
        VoiceOption("multi_male_joker_v3_uranus_bigtts", "Joker 3.0", "豆包 3.0 · 英语（美式口音）", Gender.MALE),
    )

    val ALL = FEMALE + MALE

    /** 按性别取列表；未知性别时给女声。 */
    fun forGender(gender: Gender): List<VoiceOption> = when (gender) {
        Gender.MALE -> MALE
        Gender.FEMALE -> FEMALE
        Gender.UNSPECIFIED -> FEMALE
    }

    /** 音色 id 是否属于 3.0。 */
    fun is3(id: String): Boolean = ALL.any { it.id == id }

    /** 按 id 找音色；找不到返回 null。 */
    fun of(id: String): VoiceOption? = ALL.firstOrNull { it.id == id }
}
