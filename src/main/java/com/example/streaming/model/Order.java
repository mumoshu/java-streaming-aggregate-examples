package com.example.streaming.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Domain model representing an order with an ID, amount, and status.
 * This is the entity type that will be streamed from the paginated API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Order(
        String id,
        double amount,
        String status
) {
    @JsonCreator
    public Order(
            @JsonProperty("id") String id,
            @JsonProperty("amount") double amount,
            @JsonProperty("status") String status
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.amount = amount;
        this.status = status;
    }
}
