import java.util.*;

/**
 * 地理空间规避工具类
 * 提供选址/选线规则中的地理规避功能
 * 坐标系：WGS84 经纬度坐标系（EPSG: 4326）
 */

// 经纬度点
class Point2D {
    public double x; // 经度
    public double y; // 纬度

    public Point2D() {}

    public Point2D(double x, double y) {
        this.x = x;
        this.y = y;
    }

    @Override
    public String toString() {
        return String.format("Point2D{x=%.6f, y=%.6f}", x, y);
    }
}

// 矩形范围
class Rectangle2D {
    public Point2D leftBottom;
    public Point2D rightTop;

    public Rectangle2D() {
        this.leftBottom = new Point2D();
        this.rightTop = new Point2D();
    }

    public Rectangle2D(Point2D leftBottom, Point2D rightTop) {
        this.leftBottom = leftBottom;
        this.rightTop = rightTop;
    }

    /**
     * 检查当前矩形是否与另一个矩形相交
     */
    public boolean intersects(Rectangle2D other) {
        return !(this.rightTop.x < other.leftBottom.x ||
                 this.leftBottom.x > other.rightTop.x ||
                 this.rightTop.y < other.leftBottom.y ||
                 this.leftBottom.y > other.rightTop.y);
    }

    @Override
    public String toString() {
        return String.format("Rectangle2D{leftBottom=%s, rightTop=%s}", leftBottom, rightTop);
    }
}

// 规避数据结果
class AvoidData {
    public String srcDataID;   // 原始数据
    public String avoidID;     // 规避数据集ID
    public String avoidName;   // 规避区名称
    public String avoidType;   // 规避区类型
    public Rectangle2D avoidBounds; // 规避区范围
    public String description; // 描述信息
    public double[][] polygonCoords; // 多边形坐标 [[lon, lat], ...]

    public AvoidData() {}

    public AvoidData(String srcDataID, String avoidID, String avoidName, String avoidType, Rectangle2D avoidBounds, String description) {
        this.srcDataID = srcDataID;
        this.avoidID = avoidID;
        this.avoidName = avoidName;
        this.avoidType = avoidType;
        this.avoidBounds = avoidBounds;
        this.description = description;
    }

    public AvoidData(String srcDataID, String avoidID, String avoidName, String avoidType, Rectangle2D avoidBounds, String description, double[][] polygonCoords) {
        this.srcDataID = srcDataID;
        this.avoidID = avoidID;
        this.avoidName = avoidName;
        this.avoidType = avoidType;
        this.avoidBounds = avoidBounds;
        this.description = description;
        this.polygonCoords = polygonCoords;
    }

    @Override
    public String toString() {
        return String.format("AvoidData{srcDataID='%s', avoidID='%s', avoidName='%s', avoidType='%s'}", 
                srcDataID, avoidID, avoidName, avoidType);
    }
}

/**
 * 地理空间规避工具类
 * 坐标系：WGS84 经纬度坐标系（EPSG: 4326）
 */
public class GeoAvoidTools {

    // ==================== 写死的选址数据 ====================
    // 坐标系：WGS84 经纬度坐标系（EPSG: 4326）

    /**
     * 永久基本农田数据 - 用于 avoidZone
     */
    private static final List<AvoidData> FARMLAND_DATA = Arrays.asList(
        new AvoidData("farmland", "FM001", "朝阳区永久基本农田-1", "永久基本农田",
            new Rectangle2D(new Point2D(116.480, 39.920), new Point2D(116.500, 39.940)),
            "北京市朝阳区永久基本农田保护区，禁止建设",
            new double[][]{{116.480, 39.920}, {116.488, 39.918}, {116.495, 39.925}, {116.500, 39.932}, {116.498, 39.940}, {116.488, 39.942}, {116.480, 39.938}, {116.478, 39.928}}),
        new AvoidData("farmland", "FM002", "海淀区永久基本农田-1", "永久基本农田",
            new Rectangle2D(new Point2D(116.280, 39.960), new Point2D(116.310, 39.990)),
            "北京市海淀区永久基本农田保护区，禁止建设",
            new double[][]{{116.280, 39.960}, {116.290, 39.958}, {116.300, 39.965}, {116.310, 39.975}, {116.308, 39.988}, {116.295, 39.992}, {116.282, 39.985}, {116.278, 39.972}}),
        new AvoidData("farmland", "FM003", "通州区永久基本农田-1", "永久基本农田",
            new Rectangle2D(new Point2D(116.650, 39.880), new Point2D(116.700, 39.920)),
            "北京市通州区永久基本农田保护区，禁止建设",
            new double[][]{{116.650, 39.880}, {116.665, 39.878}, {116.680, 39.888}, {116.700, 39.895}, {116.698, 39.915}, {116.682, 39.922}, {116.655, 39.918}, {116.648, 39.895}}),
        new AvoidData("farmland", "FM004", "大兴区永久基本农田-1", "永久基本农田",
            new Rectangle2D(new Point2D(116.350, 39.750), new Point2D(116.400, 39.800)),
            "北京市大兴区永久基本农田保护区，禁止建设",
            new double[][]{{116.350, 39.750}, {116.365, 39.748}, {116.380, 39.758}, {116.400, 39.765}, {116.398, 39.785}, {116.382, 39.792}, {116.355, 39.788}, {116.348, 39.765}})
    );

    /**
     * 冰区数据 - 用于 avoidZone
     */
    private static final List<AvoidData> ICE_ZONE_DATA = Arrays.asList(
        new AvoidData("ice_zone", "IZ001", "张家口冰区-1", "冰区",
            new Rectangle2D(new Point2D(114.850, 40.750), new Point2D(115.000, 40.900)),
            "张家口地区重冰区，线路设计需考虑覆冰厚度"),
        new AvoidData("ice_zone", "IZ002", "承德冰区-1", "冰区",
            new Rectangle2D(new Point2D(117.800, 40.900), new Point2D(118.000, 41.100)),
            "承德地区重冰区，线路设计需考虑覆冰厚度"),
        new AvoidData("ice_zone", "IZ003", "延庆冰区-1", "冰区",
            new Rectangle2D(new Point2D(115.900, 40.400), new Point2D(116.100, 40.600)),
            "延庆地区中冰区，线路设计需考虑覆冰厚度")
    );

    /**
     * 微地形数据 - 用于 avoidZone
     */
    private static final List<AvoidData> MICRO_TERRAIN_DATA = Arrays.asList(
        new AvoidData("micro_terrain", "MT001", "门头沟微地形-1", "微地形",
            new Rectangle2D(new Point2D(115.800, 39.900), new Point2D(115.950, 40.050)),
            "门头沟山区微地形区，风场复杂，需特殊设计"),
        new AvoidData("micro_terrain", "MT002", "房山微地形-1", "微地形",
            new Rectangle2D(new Point2D(115.700, 39.600), new Point2D(115.850, 39.750)),
            "房山山区微地形区，风场复杂，需特殊设计")
    );

    /**
     * 采石场爆炸作业区数据 - 用于 avoidBufferConstant
     */
    private static final List<AvoidData> QUARRY_DATA = Arrays.asList(
        new AvoidData("quarry", "QY001", "密云采石场-1", "采石场爆炸作业区",
            new Rectangle2D(new Point2D(116.750, 40.450), new Point2D(116.800, 40.500)),
            "密云采石场，安全距离500米"),
        new AvoidData("quarry", "QY002", "怀柔采石场-1", "采石场爆炸作业区",
            new Rectangle2D(new Point2D(116.550, 40.350), new Point2D(116.600, 40.400)),
            "怀柔采石场，安全距离500米"),
        new AvoidData("quarry", "QY003", "平谷采石场-1", "采石场爆炸作业区",
            new Rectangle2D(new Point2D(117.050, 40.150), new Point2D(117.100, 40.200)),
            "平谷采石场，安全距离500米")
    );

    /**
     * 加油站数据 - 用于 avoidBufferConstant
     */
    private static final List<AvoidData> GAS_STATION_DATA = Arrays.asList(
        new AvoidData("gas_station", "GS001", "朝阳加油站-1", "加油站",
            new Rectangle2D(new Point2D(116.450, 39.920), new Point2D(116.460, 39.930)),
            "朝阳区加油站，安全距离200米"),
        new AvoidData("gas_station", "GS002", "海淀加油站-1", "加油站",
            new Rectangle2D(new Point2D(116.300, 39.960), new Point2D(116.310, 39.970)),
            "海淀区加油站，安全距离200米"),
        new AvoidData("gas_station", "GS003", "丰台加油站-1", "加油站",
            new Rectangle2D(new Point2D(116.280, 39.850), new Point2D(116.290, 39.860)),
            "丰台区加油站，安全距离200米")
    );

    /**
     * 加气站数据 - 用于 avoidBufferConstant
     */
    private static final List<AvoidData> CNG_STATION_DATA = Arrays.asList(
        new AvoidData("cng_station", "CS001", "通州加气站-1", "加气站",
            new Rectangle2D(new Point2D(116.680, 39.900), new Point2D(116.690, 39.910)),
            "通州区加气站，安全距离300米"),
        new AvoidData("cng_station", "CS002", "顺义加气站-1", "加气站",
            new Rectangle2D(new Point2D(116.650, 40.120), new Point2D(116.660, 40.130)),
            "顺义区加气站，安全距离300米")
    );

    /**
     * 历史文化遗迹数据 - 用于 avoidBufferExpression
     */
    private static final List<AvoidData> HERITAGE_DATA = Arrays.asList(
        new AvoidData("heritage", "HG001", "故宫缓冲区", "历史文化遗迹",
            new Rectangle2D(new Point2D(116.380, 39.900), new Point2D(116.420, 39.950)),
            "故宫世界文化遗产，缓冲区距离=level*100米，level=5"),
        new AvoidData("heritage", "HG002", "天坛缓冲区", "历史文化遗迹",
            new Rectangle2D(new Point2D(116.380, 39.850), new Point2D(116.420, 39.900)),
            "天坛世界文化遗产，缓冲区距离=level*80米，level=4"),
        new AvoidData("heritage", "HG003", "颐和园缓冲区", "历史文化遗迹",
            new Rectangle2D(new Point2D(116.250, 39.980), new Point2D(116.300, 40.030)),
            "颐和园世界文化遗产，缓冲区距离=level*60米，level=3"),
        new AvoidData("heritage", "HG004", "长城缓冲区-八达岭", "历史文化遗迹",
            new Rectangle2D(new Point2D(116.000, 40.350), new Point2D(116.100, 40.450)),
            "八达岭长城世界文化遗产，缓冲区距离=level*150米，level=5")
    );

    /**
     * 矿产敏感区数据 - 用于 avoidBufferExpression
     */
    private static final List<AvoidData> MINING_DATA = Arrays.asList(
        new AvoidData("mining", "MN001", "房山煤矿区", "矿产敏感区",
            new Rectangle2D(new Point2D(115.700, 39.600), new Point2D(115.850, 39.750)),
            "房山煤矿开采区，缓冲区距离=(10-level)*50米"),
        new AvoidData("mining", "MN002", "门头沟煤矿区", "矿产敏感区",
            new Rectangle2D(new Point2D(115.800, 39.900), new Point2D(115.950, 40.050)),
            "门头沟煤矿开采区，缓冲区距离=(10-level)*50米"),
        new AvoidData("mining", "MN003", "密云铁矿", "矿产敏感区",
            new Rectangle2D(new Point2D(116.700, 40.400), new Point2D(116.850, 40.550)),
            "密云铁矿开采区，缓冲区距离=(10-level)*80米")
    );

    // ==================== 数据查询方法 ====================

    /**
     * 根据数据集ID获取对应的数据列表
     */
    private static List<AvoidData> getDataByDataset(String srcDataset) {
        if (srcDataset == null) return Collections.emptyList();
        
        switch (srcDataset.toLowerCase()) {
            case "farmland":
            case "永久基本农田":
                return FARMLAND_DATA;
            case "ice_zone":
            case "冰区":
                return ICE_ZONE_DATA;
            case "micro_terrain":
            case "微地形":
                return MICRO_TERRAIN_DATA;
            case "quarry":
            case "采石场":
                return QUARRY_DATA;
            case "gas_station":
            case "加油站":
                return GAS_STATION_DATA;
            case "cng_station":
            case "加气站":
                return CNG_STATION_DATA;
            case "heritage":
            case "历史文化遗迹":
                return HERITAGE_DATA;
            case "mining":
            case "矿产":
                return MINING_DATA;
            default:
                return Collections.emptyList();
        }
    }

    /**
     * 检查选址范围是否与规避区相交
     */
    private static AvoidData findIntersectingAvoid(Rectangle2D bounds, List<AvoidData> avoidList) {
        if (bounds == null || avoidList == null) return null;
        
        for (AvoidData avoid : avoidList) {
            if (avoid.avoidBounds != null && bounds.intersects(avoid.avoidBounds)) {
                return avoid;
            }
        }
        return null;
    }

    // ==================== 公共API方法 ====================

    /**
     * 无缓冲区规避
     * 匹配：【选址规则】永久基本农田规避；【选线规则】冰区、微地形规避
     *
     * @param bounds           选址范围
     * @param srcDataset       原始面数据集ID
     * @param filterExpression 用于筛选的表达式（可选），如"level==1"
     * @return AvoidData或null（选址区域内无规避区）
     */
    public static AvoidData avoidZone(Rectangle2D bounds, String srcDataset, String filterExpression) {
        System.err.println("[Method Call] avoidZone(bounds=" + bounds + ", srcDataset=" + srcDataset + 
                        ", filterExpression=" + filterExpression + ")");

        List<AvoidData> dataList = getDataByDataset(srcDataset);
        AvoidData result = findIntersectingAvoid(bounds, dataList);
        
        if (result != null) {
            System.err.println("[Method Result] avoidZone -> 发现规避区: " + result);
            return result;
        }

        System.err.println("[Method Result] avoidZone -> null (选址区域内无规避区)");
        return null;
    }

    /**
     * 常量缓冲区范围规避
     * 匹配：【选址规则】暂无；【选线规则】采石场爆炸作业区规避；加油、加气站及设施规避
     *
     * @param bounds    选址范围
     * @param srcDataset 原始面数据集ID
     * @param avoidance 规避距离（米），缓冲区范围
     * @return AvoidData或null（选址区域内无规避区）
     */
    public static AvoidData avoidBufferConstant(Rectangle2D bounds, String srcDataset, double avoidance) {
        System.err.println("[Method Call] avoidBufferConstant(bounds=" + bounds + ", srcDataset=" + srcDataset + 
                        ", avoidance=" + avoidance + "m)");

        // 将缓冲区距离转换为经纬度偏移（近似值）
        // 在纬度39度附近，1度经度 ≈ 85km，1度纬度 ≈ 111km
        double bufferLon = avoidance / 85000.0;  // 米转经度
        double bufferLat = avoidance / 111000.0; // 米转纬度
        
        // 扩展选址范围以包含缓冲区
        Rectangle2D bufferedBounds = new Rectangle2D(
            new Point2D(bounds.leftBottom.x - bufferLon, bounds.leftBottom.y - bufferLat),
            new Point2D(bounds.rightTop.x + bufferLon, bounds.rightTop.y + bufferLat)
        );

        List<AvoidData> dataList = getDataByDataset(srcDataset);
        AvoidData result = findIntersectingAvoid(bufferedBounds, dataList);
        
        if (result != null) {
            System.err.println("[Method Result] avoidBufferConstant -> 发现规避区: " + result);
            return result;
        }

        System.err.println("[Method Result] avoidBufferConstant -> null (选址区域内无规避区)");
        return null;
    }

    /**
     * 字段缓冲区范围规避
     * 匹配：【选址规则】历史文化遗迹、矿产等敏感区规避；【选线规则】暂无
     *
     * @param bounds          选址范围
     * @param srcDataset      原始敏感区数据集ID
     * @param bufferExpression 规避距离字段名或字段表达式，如"level"、"(10-level)*8"
     * @return AvoidData或null（选址区域内无规避区）
     */
    public static AvoidData avoidBufferExpression(Rectangle2D bounds, String srcDataset, String bufferExpression) {
        System.err.println("[Method Call] avoidBufferExpression(bounds=" + bounds + ", srcDataset=" + srcDataset + 
                        ", bufferExpression=" + bufferExpression + ")");

        // 根据表达式计算缓冲区距离
        double bufferDistance = 0;
        if (bufferExpression != null) {
            bufferExpression = bufferExpression.toLowerCase().trim();
            switch (bufferExpression) {
                case "level":
                case "level*100":
                    bufferDistance = 500; // 默认500米
                    break;
                case "(10-level)*50":
                    bufferDistance = 300; // 默认300米
                    break;
                case "(10-level)*80":
                    bufferDistance = 400; // 默认400米
                    break;
                default:
                    bufferDistance = 500; // 默认500米
            }
        }

        // 将缓冲区距离转换为经纬度偏移
        double bufferLon = bufferDistance / 85000.0;
        double bufferLat = bufferDistance / 111000.0;
        
        // 扩展选址范围以包含缓冲区
        Rectangle2D bufferedBounds = new Rectangle2D(
            new Point2D(bounds.leftBottom.x - bufferLon, bounds.leftBottom.y - bufferLat),
            new Point2D(bounds.rightTop.x + bufferLon, bounds.rightTop.y + bufferLat)
        );

        List<AvoidData> dataList = getDataByDataset(srcDataset);
        AvoidData result = findIntersectingAvoid(bufferedBounds, dataList);
        
        if (result != null) {
            System.err.println("[Method Result] avoidBufferExpression -> 发现规避区: " + result);
            return result;
        }

        System.err.println("[Method Result] avoidBufferExpression -> null (选址区域内无规避区)");
        return null;
    }
}