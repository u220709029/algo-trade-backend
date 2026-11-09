package com.example.demo.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SignalRecord {
    @Id
    private String signalId;
    private String symbol;
    private String action;
    private String orderStatus;
    private Long processedAt;
}
