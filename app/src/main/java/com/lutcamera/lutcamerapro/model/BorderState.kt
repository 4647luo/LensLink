package com.lutcamera.lutcamerapro.model

/**
 * 🏷️ 边框分类
 */
enum class BorderCategory(val title: String) {
    ALL("全部"),
    CLASSIC("大厂经典"),
    FILM("复古胶片"),
    GALLERY("画廊装裱"),
    CREATIVE("创意版式")
}

/**
 * 🖼️ 30 款风格高度差异化、完全不重样的专业边框模板 */
enum class BorderMode(
    val title: String,
    val subtitle: String,
    val category: BorderCategory
) {
    // === 1. 大厂经典系列 (6 款) ===
    LEICA_CLASSIC_WHITE("Leica Classic White", "Pure white frame", BorderCategory.CLASSIC),
    LEICA_RED_DOT_PRO("徕卡红标签名", "典藏红圈 · 大师签名", BorderCategory.CLASSIC),
    HASSELBLAD_ORANGE("哈苏橙标方框", "中画幅经典 · 橙标方框", BorderCategory.CLASSIC),
    SONY_ALPHA_STUDIO("索尼影棚白框", "极简科技 · 希腊阿尔法标", BorderCategory.CLASSIC),
    CANON_RED_RING("佳能红圈典藏", "奢华红圈 · 金色参数", BorderCategory.CLASSIC),
    NIKON_HERITAGE_GOLD("尼康复古铭牌", "深炭黑底 · 复古黄铜铭牌", BorderCategory.CLASSIC),

    // === 2. 复古胶片系列 (9 款) ===
    FUJI_INSTAX_SQUARE("Fuji Instax Square", "Instant film white", BorderCategory.FILM),
    FUJI_CLASSIC_CHROME("富士正片印记", "经典正片 · 齿孔色带", BorderCategory.FILM),
    KODAK_GOLD_200("Kodak Gold 200", "Warm gold tone film", BorderCategory.FILM),
    KODAK_PORTRA_160("柯达波特拉 160", "温润米白 · 药膜微划", BorderCategory.FILM),
    POLAROID_VINTAGE("Polaroid Vintage", "Retro polaroid style", BorderCategory.FILM),
    VINTAGE_KRAFT_PAPER("Vintage Kraft Paper", "Kraft textured border", BorderCategory.FILM),
    STREET_DARKROOM("Street Darkroom", "Black and white darkroom", BorderCategory.FILM),
    SLIDE_MOUNT_35MM("35mm反转片夹", "白色塑料装片框 · 槽位标记", BorderCategory.FILM),
    CONTACT_SHEET_STRIP("底片联排印样", "黑白冲印 · 连续底片序列", BorderCategory.FILM),

    // === 3. 画廊装裱系列 (5 款) ===
    GALLERY_PASSEPARTOUT("Gallery Passepartout", "Double mat passepartout", BorderCategory.GALLERY),
    FLOATING_SHADOW_MAT("Floating Shadow Mat", "Floating shadow frame", BorderCategory.GALLERY),
    MATTE_BLACK_STUDIO("Matte Black Studio", "Matte black border", BorderCategory.GALLERY),
    CHAMPAGNE_GOLD_LINE("Champagne Gold Line", "Champagne gold accent", BorderCategory.GALLERY),
    MODERN_DOUBLE_LINE("现代实虚双线", "双层边框 · 外实内虚平衡", BorderCategory.GALLERY),

    // === 4. 创意版式系列 (10 款) ===
                CINEMATIC_LETTERBOX("电影宽银幕 2.35", "宽银幕上下黑边 · 经典字幕", BorderCategory.CREATIVE),
    EDITORIAL_GAZETTE("复古新闻报刊", "双栏排版 · 铅印报头", BorderCategory.CREATIVE),
    SWISS_TYPOGRAPHY("瑞士版式海报", "大字重等线 · 国际主义", BorderCategory.CREATIVE),
    MAGAZINE_COVER("时尚杂志封面", "跨图刊名 · 条形码与期号", BorderCategory.CREATIVE),
    TRAVELER_POSTCARD("Traveler Postcard", "Postcard stamp style", BorderCategory.CREATIVE),
    HANDWRITTEN_MEMO("Handwritten Memo", "Handwritten note memo", BorderCategory.CREATIVE),
    CYBERPUNK_HUD("电子取景器HUD", "绿色十字准心 · 刻度参数", BorderCategory.CREATIVE),
    ASYMMETRIC_BALANCE("包豪斯非对称", "几何色块 · 留白平衡", BorderCategory.CREATIVE),
    MINIMAL_CORNER_BADGE("极简浮雕徽章", "满幅微边 · 磨砂悬浮印章", BorderCategory.CREATIVE),
    BLUR_AMBIENT_GLOW("Blur Ambient Glow", "Soft ambient blur frame", BorderCategory.CREATIVE)
}

enum class WatermarkFont {
    SANS_SERIF, SERIF, MONOSPACE, HANDWRITTEN
}

enum class WatermarkLayout {
    VERTICAL,   // 经典居中排版
                HORIZONTAL  // 左右横向分栏
                }

data class BorderState(
    val mode: BorderMode = BorderMode.LEICA_CLASSIC_WHITE,
    val paddingTop: Float = 0.04f,
    val paddingBottom: Float = 0.08f,
    val paddingLeft: Float = 0.04f,
    val paddingRight: Float = 0.04f,
    val cornerRadius: Float = 0f,
    val shadowElevation: Float = 0f,
    val showExif: Boolean = true,
    val fontType: WatermarkFont = WatermarkFont.SANS_SERIF,
    val watermarkLayout: WatermarkLayout = WatermarkLayout.VERTICAL,

    val textColorInt: Int = 0,        // 0 为智能黑白反转，>0 为自定义颜色
                val fontSizeScale: Float = 1.0f,  // 字体缩放比例
                val watermarkOffsetX: Float = 0f,
    val watermarkOffsetY: Float = 0f,
    val isCustomWatermark: Boolean = false,
    val customLine1: String = "LutCamera Pro",
    val customLine2: String = "Shot on Digital Camera",
    val showLogo: Boolean = true
)





