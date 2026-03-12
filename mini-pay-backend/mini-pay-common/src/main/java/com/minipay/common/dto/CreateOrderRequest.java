package com.minipay.common.dto;

public class CreateOrderRequest {
    private Long productId;
    private Integer quantity;

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    @Override
    public String toString() {
        return "CreateOrderRequest{" +
                "productId=" + productId +
                ", quantity=" + quantity +
                '}';
    }
}
