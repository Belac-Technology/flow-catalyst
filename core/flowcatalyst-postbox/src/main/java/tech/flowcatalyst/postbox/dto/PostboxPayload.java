package tech.flowcatalyst.postbox.dto;

import java.util.Map;

public class PostboxPayload {
    public String id;
    public Long tenantId;
    public String partitionId;
    public String type;
    public String payload;
    public Map<String, Object> headers;

    public PostboxPayload() {
    }

    public PostboxPayload(String id, Long tenantId, String partitionId, String type, String payload) {
        this.id = id;
        this.tenantId = tenantId;
        this.partitionId = partitionId;
        this.type = type;
        this.payload = payload;
    }

    public PostboxPayload(String id, Long tenantId, String partitionId, String type, String payload, Map<String, Object> headers) {
        this.id = id;
        this.tenantId = tenantId;
        this.partitionId = partitionId;
        this.type = type;
        this.payload = payload;
        this.headers = headers;
    }
}
