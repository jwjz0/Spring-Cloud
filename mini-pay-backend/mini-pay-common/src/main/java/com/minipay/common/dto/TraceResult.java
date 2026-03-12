package com.minipay.common.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 带调用链追踪的结果包装
 */
public class TraceResult<T> {
    private T data;
    private List<TraceStep> trace;

    public TraceResult() {
        this.trace = new ArrayList<>();
    }

    public TraceResult(T data, List<TraceStep> trace) {
        this.data = data;
        this.trace = trace;
    }

    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
    public List<TraceStep> getTrace() { return trace; }
    public void setTrace(List<TraceStep> trace) { this.trace = trace; }

    public void addStep(String service, String action, String tech, String detail, String status, long timestamp, long duration) {
        this.trace.add(new TraceStep(service, action, tech, detail, status, timestamp, duration));
    }
}
