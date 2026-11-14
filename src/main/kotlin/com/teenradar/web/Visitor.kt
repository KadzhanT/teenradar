package com.teenradar.web

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/** Anonymous visitor identified by the `vid` cookie. No accounts: the jury must not need to sign up. */
@Entity
@Table(name = "visitors")
class Visitor(
    @Id var id: UUID,
    var premium: Boolean = false,
    var decodeDay: LocalDate? = null,
    var decodeCount: Int = 0,
)

interface VisitorRepository : JpaRepository<Visitor, UUID> {
    @Transactional
    @Modifying
    @Query("insert into visitors (id) values (:id) on conflict do nothing", nativeQuery = true)
    fun ensure(id: UUID)

    /** Atomically spends one free decode. Returns 0 when today's limit is used up (premium is unlimited). */
    @Transactional
    @Modifying
    @Query(
        """
        update visitors
        set decode_count = case when decode_day = :today then decode_count + 1 else 1 end,
            decode_day = :today
        where id = :id and (premium or decode_day is distinct from :today or decode_count < :limit)
        """,
        nativeQuery = true,
    )
    fun spendDecode(id: UUID, today: LocalDate, limit: Int): Int

    @Transactional
    @Modifying
    @Query("update visitors set decode_count = greatest(decode_count - 1, 0) where id = :id", nativeQuery = true)
    fun refundDecode(id: UUID): Int

    @Transactional
    @Modifying
    @Query("update visitors set premium = true where id = :id", nativeQuery = true)
    fun markPremium(id: UUID): Int
}
