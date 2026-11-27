package pl.evcharging.backend.energy;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

import java.util.List;

@RestController
@RequestMapping("/api/energy")
public class EnergyController {

    private final EnergyMixService energyMixService;

    public EnergyController(EnergyMixService energyMixService) {
        this.energyMixService = energyMixService;
    }

    @GetMapping("/mix")
    public List<DailyEnergyMix> getDailyMix() {
        return energyMixService.getDailyMix();
    }

    @GetMapping("/best-window")
    public ResponseEntity<?> bestWindow(@RequestParam(value = "hours", required = false) String hours) {
        if (hours == null || !hours.matches("0*[1-6]")) {
            return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "Parametr hours musi być liczbą całkowitą od 1 do 6."));
        }
        return energyMixService.findBestWindow(Integer.parseInt(hours))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @ExceptionHandler(RestClientException.class)
    public ProblemDetail handleUpstreamError() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "Nie udało się pobrać danych z Carbon Intensity API.");
    }
}
