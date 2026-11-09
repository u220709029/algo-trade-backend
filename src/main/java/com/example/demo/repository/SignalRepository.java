package com.example.demo.repository;

import com.example.demo.entity.SignalRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface SignalRepository extends JpaRepository<SignalRecord, String> {

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO signal_record (signal_id, symbol, action, order_status, processed_at) VALUES (:id, :symbol, :action, :status, :time)", nativeQuery = true)
    void insertSignalStrict(@Param("id") String id, 
                            @Param("symbol") String symbol, 
                            @Param("action") String action, 
                            @Param("status") String status, 
                            @Param("time") Long time);
}
