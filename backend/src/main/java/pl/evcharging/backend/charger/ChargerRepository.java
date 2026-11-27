package pl.evcharging.backend.charger;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ChargerRepository extends JpaRepository<Charger, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select charger from Charger charger where charger.id = :id")
    Optional<Charger> findByIdForUpdate(@Param("id") long id);
}
