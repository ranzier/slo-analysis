package co.bilibili.slo.util;

import java.util.Map;

public final class ApiNameParser {

    private ApiNameParser() {}

    private static final String SERVER_PATH = "server.path";
    private static final String _SERVER_PATH = "_server_path";

    private static final Map<String, String> APP_QUERY_FIELD = Map.of(
            "main.bangumi.season-service", _SERVER_PATH,
            "open.bangumi.view-gateway", _SERVER_PATH,
            "ogv.player.authunite-service-ogv", SERVER_PATH
    );

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

    public static String buildLogQuery(String appPath, String apiPath, String retCode) {
        String field = APP_QUERY_FIELD.getOrDefault(appPath, _SERVER_PATH);
        if (SERVER_PATH.equals(field)) {
            String path = apiPath.startsWith("/") ? apiPath : "/" + apiPath;
            return String.format("server.path IN ('%s') AND ret IN (%s)", path, retCode);
        }
        return String.format("_server_path = '%s' AND ret = '%s'", apiPath, retCode);
    }

    private static boolean isGrpcPath(String path) {
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        int slashIdx = normalized.indexOf('/');
        if (slashIdx <= 0) return false;
        String servicePart = normalized.substring(0, slashIdx);
        return servicePart.contains(".") && servicePart.chars().filter(c -> c == '.').count() >= 2;
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
