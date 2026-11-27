package pl.evcharging.backend.charger;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(path = "/api/chargers")
public class ChargerController {

    private final ChargerRepository repository;

    public ChargerController(ChargerRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<ChargerResponse> findAllChargers() {
        return repository.findAll().stream()
                .map(charger -> new ChargerResponse(charger.getId(), charger.getName(), charger.getLocation()))
                .toList();
    }
}
