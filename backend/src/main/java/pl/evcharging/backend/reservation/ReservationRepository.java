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

    List<Reservation> findAllByChargerIdAndStartsAtLessThanAndEndsAtGreaterThanOrderByStartsAtAsc(
            long chargerId, Instant to, Instant from);

    boolean existsByChargerIdAndStartsAtLessThanAndEndsAtGreaterThan(long chargerId, Instant to, Instant from);

    @Modifying
    @Query("delete from Reservation reservation where reservation.id = :id and reservation.ownerId = :ownerId")
    int deleteOwned(@Param("id") long id, @Param("ownerId") UUID ownerId);
}
