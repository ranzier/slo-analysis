package co.bilibili.slo.model;

import java.util.Map;

/**
 * 日志条目，字段不固定，用 Map 存储
 */
public record LogEntry(Map<String, Object> fields) {

    public String getString(String key) {
        Object v = fields.get(key);
        return v != null ? v.toString() : "";
    }

    public int getInt(String key) {
        Object v = fields.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    public long getLong(String key) {
        Object v = fields.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    public double getDouble(String key) {
        Object v = fields.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }
}
