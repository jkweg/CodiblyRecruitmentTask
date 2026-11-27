package pl.evcharging.backend.reservation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findAllByOwnerIdOrderByStartsAtAscIdAsc(UUID ownerId);

    @Query("""
            select reservation from Reservation reservation
            where reservation.chargerId = :chargerId
              and reservation.startsAt < :rangeEnd
              and reservation.endsAt > :rangeStart
            order by reservation.startsAt asc
            """)
    List<Reservation> findBusyPeriods(
            @Param("chargerId") long chargerId, @Param("rangeStart") Instant rangeStart,
            @Param("rangeEnd") Instant rangeEnd);

    @Query("""
            select exists (
                select 1 from Reservation reservation
                where reservation.chargerId = :chargerId
                  and reservation.startsAt < :requestedEnd
                  and reservation.endsAt > :requestedStart
            )
            """)
    boolean hasConflict(
            @Param("chargerId") long chargerId, @Param("requestedStart") Instant requestedStart,
            @Param("requestedEnd") Instant requestedEnd);

    @Modifying
    @Query("delete from Reservation reservation where reservation.id = :id and reservation.ownerId = :ownerId")
    int deleteOwned(@Param("id") long id, @Param("ownerId") UUID ownerId);
}
