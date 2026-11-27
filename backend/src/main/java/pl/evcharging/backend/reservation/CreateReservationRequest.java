package pl.evcharging.backend.reservation;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.exc.InputCoercionException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record CreateReservationRequest(
        @JsonDeserialize(using = CreateReservationRequest.ChargerIdDeserializer.class) Long chargerId,
        String start,
        @JsonDeserialize(using = CreateReservationRequest.HoursDeserializer.class) Integer hours
) {
    static final String INVALID_CHARGER_ID = "Podaj dodatni identyfikator ładowarki (chargerId).";
    static final String INVALID_HOURS = "Czas rezerwacji musi wynosić od 1 do 6 pełnych godzin.";

    static final class ChargerIdDeserializer extends ValueDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                try {
                    return parser.getLongValue();
                } catch (InputCoercionException ignored) {
                    // Use the same field error for overflow and invalid token types.
                }
            }
            return context.reportInputMismatch(Long.class, INVALID_CHARGER_ID);
        }
    }

    static final class HoursDeserializer extends ValueDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                try {
                    return parser.getIntValue();
                } catch (InputCoercionException ignored) {
                    // Use the same field error for overflow and invalid token types.
                }
            }
            return context.reportInputMismatch(Integer.class, INVALID_HOURS);
        }
    }
}
