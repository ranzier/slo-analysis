package co.bilibili.slo.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

public final class DateParser {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static final List<DateTimeFormatter> DATETIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
    );

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("MM-dd"),
            DateTimeFormatter.ofPattern("M月d日"),
            DateTimeFormatter.ofPattern("M月d")
    );

    private DateParser() {}

    public static long parseToEpochSecond(String dateStr) {
        dateStr = dateStr.strip();

        for (DateTimeFormatter fmt : DATETIME_FORMATS) {
            try {
                LocalDateTime dt = LocalDateTime.parse(dateStr, fmt);
                return dt.atZone(ZONE).toEpochSecond();
            } catch (DateTimeParseException ignored) {}
        }

        int year = LocalDate.now().getYear();

        // 手动解析 "X月Y" 或 "X月Y日" 格式（可带时间）
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(\\d{1,2})月(\\d{1,2})日?(?:\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?$").matcher(dateStr);
        if (m.matches()) {
            int month = Integer.parseInt(m.group(1));
            int day = Integer.parseInt(m.group(2));
            LocalDate date = LocalDate.of(year, month, day);
            if (m.group(3) != null) {
                int hour = Integer.parseInt(m.group(3));
                int minute = Integer.parseInt(m.group(4));
                int second = m.group(5) != null ? Integer.parseInt(m.group(5)) : 0;
                LocalDateTime dt = date.atTime(hour, minute, second);
                return dt.atZone(ZONE).toEpochSecond();
            }
            return date.atStartOfDay(ZONE).toEpochSecond();
        }

        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                LocalDate date = LocalDate.parse(dateStr, fmt);
                if (date.getYear() == 0 || date.getYear() < 2000) {
                    date = date.withYear(year);
                }
                return date.atStartOfDay(ZONE).toEpochSecond();
            } catch (DateTimeParseException ignored) {}
        }

        throw new IllegalArgumentException(
                "无法解析日期: " + dateStr + "，支持格式: 5月18, 05-18, 2026-05-18, 2026-05-18 17:06:30");
    }

    /**
     * 解析日期为当天 [0:00, 24:00) 的时间戳对
     */
    public static long[] parseDayRange(String dateStr) {
        long dayStart = parseToEpochSecond(dateStr);
        return new long[]{dayStart, dayStart + 86400};
    }

    public static String epochToDatetime(long epochSecond) {
        return LocalDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(epochSecond), ZONE
        ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    public static String epochToDate(long epochSecond) {
        return LocalDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(epochSecond), ZONE
        ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    public static List<long[]> parseMultipleDayRanges(String targets) {
        List<long[]> ranges = new java.util.ArrayList<>();
        for (String part : targets.split(",")) {
            ranges.add(parseDayRange(part.strip()));
        }
        return ranges;
    }

    public static List<long[]> recentDayRanges(int days) {
        List<long[]> ranges = new java.util.ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = days; i >= 1; i--) {
            LocalDate d = today.minusDays(i);
            long start = d.atStartOfDay(ZONE).toEpochSecond();
            ranges.add(new long[]{start, start + 86400});
        }
        return ranges;
    }
}
