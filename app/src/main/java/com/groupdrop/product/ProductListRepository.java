package com.groupdrop.product;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 목록 전용 단일 조인 조회. */
@Repository
public class ProductListRepository {

    private static final int LIST_LIMIT = 100;
    private final JdbcTemplate jdbc;

    public ProductListRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> find(Long supplierId) {
        String filter = supplierId == null ? "" : "WHERE p.supplier_id = ?";
        List<Object> arguments = new ArrayList<>();
        if (supplierId != null) {
            arguments.add(supplierId);
        }
        arguments.add(LIST_LIMIT);
        return jdbc.query("""
                WITH listed_products AS (
                    SELECT p.id, p.supplier_id, p.name
                      FROM products p
                      %s
                     ORDER BY p.id DESC
                     LIMIT ?
                )
                SELECT p.id, p.name, p.supplier_id, s.name AS supplier_name,
                       ps.id AS sku_id, ps.option_name
                  FROM listed_products p
                  JOIN suppliers s ON s.id = p.supplier_id
                  LEFT JOIN product_skus ps ON ps.product_id = p.id
                 ORDER BY p.id DESC, ps.id ASC
                """.formatted(filter), (rs, rowNum) -> new Row(
                rs.getLong("supplier_id"), rs.getString("supplier_name"), rs.getLong("id"), rs.getString("name"),
                rs.getObject("sku_id", Long.class), rs.getString("option_name")), arguments.toArray());
    }

    public record Row(Long supplierId, String supplierName, Long id, String name, Long skuId, String optionName) { }
}
