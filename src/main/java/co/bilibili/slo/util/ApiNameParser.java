package co.bilibili.slo.util;

public final class ApiNameParser {

    private ApiNameParser() {}

    /**
     * 解析 "/path/Method:-504" → ["path/Method", "-504"]
     * 按最后一个冒号分割
     */
    public static String[] parse(String apiStr) {
        int idx = apiStr.lastIndexOf(':');
        if (idx == -1) {
            return new String[]{stripLeadingSlash(apiStr), ""};
        }
        String path = stripLeadingSlash(apiStr.substring(0, idx));
        String retCode = apiStr.substring(idx + 1);
        return new String[]{path, retCode};
    }

    public static String safeDirName(String name) {
        return name.replace("/", "_").replace(":", "_").replace(".", "_");
    }

    private static String stripLeadingSlash(String s) {
        if (s.startsWith("/")) {
            return s.substring(1);
        }
        return s;
    }
}
