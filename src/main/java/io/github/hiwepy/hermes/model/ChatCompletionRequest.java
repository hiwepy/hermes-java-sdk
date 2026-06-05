package io.github.hiwepy.hermes.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatCompletionRequest {
    private String model;
    private List<Message> messages;
    private Boolean stream;

    @JsonProperty("max_tokens")
    private Integer maxTokens;

    private Double temperature;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Message {
        private String role;
        private Object content; // String or List<ContentPart>
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContentPart {
        private String type;
        private String text;

        @com.fasterxml.jackson.annotation.JsonProperty("image_url")
        private Map<String, Object> imageUrl;
    }
}
