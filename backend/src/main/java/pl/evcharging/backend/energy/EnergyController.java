package pl.evcharging.backend.energy;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @ExceptionHandler(RestClientException.class)
    public ProblemDetail handleUpstreamError() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "Nie udało się pobrać danych z Carbon Intensity API.");
    }
}
