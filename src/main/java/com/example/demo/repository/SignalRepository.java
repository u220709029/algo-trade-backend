package com.example.demo.repository;

import com.example.demo.entity.SignalRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SignalRepository extends JpaRepository<SignalRecord, String> {
}
