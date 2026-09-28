package com.ayywl.delveforge.app.api.productdirection;

import java.util.List;

/**
 * {@code POST /api/product-directions/discovery} 的响应体。
 *
 * <p>返回的是本轮发现已经持久化的候选方向，顺序与模型给出的提案一致。它们全部处于
 * {@code CANDIDATE}：系统可以主动生成和推荐方向，但不能替用户做出选择（INV-D07）。
 *
 * <p>用一个对象包住数组而不是直接返回数组：顶层是对象，之后要在响应上补充说明性字段时
 * 不需要改变契约的形状，调用方也不必处理「有时候是数组、有时候是对象」。
 *
 * <p>本类型是 Interface Adapter 的 DTO，不是领域对象。
 *
 * @param directions 本轮发现产生的候选方向；一次发现固定产生 3 到 5 条
 */
public record ProductDirectionDiscoveryResponse(List<ProductDirectionResponse> directions) {

    public ProductDirectionDiscoveryResponse {
        if (directions == null) {
            throw new IllegalArgumentException(
                    "Product Direction Discovery 响应的 directions 不能为 null");
        }
        for (ProductDirectionResponse direction : directions) {
            if (direction == null) {
                throw new IllegalArgumentException(
                        "Product Direction Discovery 响应的 directions 不能包含 null");
            }
        }
        directions = List.copyOf(directions);
    }
}
