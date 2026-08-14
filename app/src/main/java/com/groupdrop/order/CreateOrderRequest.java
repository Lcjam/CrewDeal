package com.groupdrop.order;

import java.util.List;

public record CreateOrderRequest(List<Item> items) {
    public record Item(Long productSkuId, Integer quantity) {
    }
}
