package io.github.hiwepy.hermes.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelInfo {
    private String object;
    private List<ModelEntry> data;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelEntry {
        private String id;
        private String object;
        private String ownedBy;
    }
}
