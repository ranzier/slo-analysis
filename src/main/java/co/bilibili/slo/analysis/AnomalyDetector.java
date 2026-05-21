package co.bilibili.slo.analysis;

import co.bilibili.slo.model.AnomalyDay;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AnomalyDetector {

    @SuppressWarnings("unchecked")
    public List<AnomalyDay> detectAnomalies(Map<String, Object> sloData, double madThreshold, int maxDays) {
        Map<String, Object> dailyData = (Map<String, Object>) sloData.getOrDefault("data", Map.of());
        if (dailyData.isEmpty()) {
            return List.of();
        }

        List<String> dates = new ArrayList<>(dailyData.keySet());
        Collections.sort(dates);
        int n = dates.size();

        List<Long> serviceErrs = new ArrayList<>();
        for (String date : dates) {
            Map<String, Object> entry = (Map<String, Object>) dailyData.get(date);
            Map<String, Object> values = (Map<String, Object>) entry.get("values");
            serviceErrs.add(toLong(values.get("service_err")));
        }

        // 计算中位数
        List<Long> sorted = new ArrayList<>(serviceErrs);
        Collections.sort(sorted);
        double median = n % 2 == 1
                ? sorted.get(n / 2)
                : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;

        // 计算 MAD
        List<Double> absDevs = new ArrayList<>();
        for (long err : serviceErrs) {
            absDevs.add(Math.abs(err - median));
        }
        Collections.sort(absDevs);
        double mad = absDevs.get(absDevs.size() / 2);

        double threshold;
        if (mad == 0) {
            threshold = median > 0 ? median * 3 : 1000;
        } else {
            threshold = median + madThreshold * mad;
        }

        List<AnomalyDay> anomalies = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            String dateStr = dates.get(i);
            Map<String, Object> entry = (Map<String, Object>) dailyData.get(dateStr);
            Map<String, Object> values = (Map<String, Object>) entry.get("values");
            long serviceErr = toLong(values.get("service_err"));

            if (serviceErr <= threshold) {
                continue;
            }

            String dateShort = dateStr.substring(0, 10).replace("-", "/");
            List<String> reasons = new ArrayList<>();

            double deviation = mad > 0 ? (serviceErr - median) / mad : Double.POSITIVE_INFINITY;
            reasons.add(String.format("SERVICE 错误数 %d 偏离中位数 %.0f (%.1f MAD)", serviceErr, median, deviation));

            for (String layer : List.of("slb", "service", "apigw")) {
                List<Double> avails = new ArrayList<>();
                for (String d : dates) {
                    Map<String, Object> e = (Map<String, Object>) dailyData.get(d);
                    Map<String, Object> v = (Map<String, Object>) e.get("values");
                    avails.add(toDouble(v.get(layer + "_available")));
                }
                List<Double> sortedAvails = new ArrayList<>(avails);
                Collections.sort(sortedAvails);
                double medianAvail = sortedAvails.get(n / 2);
                double currentAvail = toDouble(values.get(layer + "_available"));
                double drop = medianAvail - currentAvail;
                if (drop > 0.01) {
                    reasons.add(String.format("%s 可用性 %.3f%% 低于中位数 %.3f%% (下降 %.3f%%)",
                            layer.toUpperCase(), currentAvail, medianAvail, drop));
                }
            }

            int alarm = toInt(values.get("alarm"));
            if (alarm > 3) {
                reasons.add(String.format("触发告警 %d 次", alarm));
            }

            double budgetSurplus = toDouble(values.getOrDefault("service_budget_surplus", 1.0));
            if (budgetSurplus < 0) {
                reasons.add(String.format("服务层错误预算已耗尽 (%.3fmin)", budgetSurplus));
            }

            anomalies.add(new AnomalyDay(dateShort, serviceErr, reasons, 0, 0));
        }

        anomalies.sort(Comparator.comparingLong(AnomalyDay::serviceErr).reversed());
        return anomalies.subList(0, Math.min(anomalies.size(), maxDays));
    }

    private long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) { try { return Long.parseLong(s); } catch (Exception e) { return 0; } }
        return 0;
    }

    private double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) { try { return Double.parseDouble(s); } catch (Exception e) { return 0; } }
        return 0;
    }

    private int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) { try { return Integer.parseInt(s); } catch (Exception e) { return 0; } }
        return 0;
    }
}
