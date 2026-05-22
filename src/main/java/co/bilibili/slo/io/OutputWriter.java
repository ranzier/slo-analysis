package co.bilibili.slo.io;

import co.bilibili.slo.config.SloProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class OutputWriter {

    private final ObjectMapper objectMapper;
    private final SloProperties props;

    public OutputWriter(ObjectMapper objectMapper, SloProperties props) {
        this.objectMapper = objectMapper;
        this.props = props;
    }

    public Path createRunDirectory(String category, String prefix) throws IOException {
        String runTs = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path dir = Path.of(props.getOutput().getBaseDir(), category, prefix + "_" + runTs);
        Files.createDirectories(dir);
        return dir;
    }

    public Path getOrCreateAppDirectory(String category, String appPath) throws IOException {
        Path dir = Path.of(props.getOutput().getBaseDir(), category, appPath);
        Files.createDirectories(dir);
        return dir;
    }

    public void writeJson(Path file, Object data) throws IOException {
        Files.createDirectories(file.getParent());
        objectMapper.writeValue(file.toFile(), data);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> readJson(Path file) {
        try {
            return objectMapper.readValue(file.toFile(), Map.class);
        } catch (IOException e) {
            return null;
        }
    }

    public void writeMarkdown(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    public String toJsonString(Object data) throws IOException {
        return objectMapper.writeValueAsString(data);
    }
}
