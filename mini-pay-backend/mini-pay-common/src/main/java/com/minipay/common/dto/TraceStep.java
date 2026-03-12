package com.minipay.common.dto;

/**
 * 调用链追踪步骤
 *
 * 面试考点 - 分布式链路追踪：
 * 生产环境用 SkyWalking / Zipkin / Jaeger 做链路追踪
 * 这里简化实现，记录每一步的服务名、操作、技术点和耗时
 */
public class TraceStep {
    private String service;      // 服务名：gateway / order-service / pay-service 等
    private String action;       // 操作描述
    private String tech;         // 涉及的技术点
    private String detail;       // 详细说明（面试知识点）
    private String status;       // success / fail / warn
    private long timestamp;      // 时间戳
    private long duration;       // 耗时(ms)

    public TraceStep() {}

    public TraceStep(String service, String action, String tech, String detail, String status, long timestamp, long duration) {
        this.service = service;
        this.action = action;
        this.tech = tech;
        this.detail = detail;
        this.status = status;
        this.timestamp = timestamp;
        this.duration = duration;
    }

    public String getService() { return service; }
    public void setService(String service) { this.service = service; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTech() { return tech; }
    public void setTech(String tech) { this.tech = tech; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public long getDuration() { return duration; }
    public void setDuration(long duration) { this.duration = duration; }
}
