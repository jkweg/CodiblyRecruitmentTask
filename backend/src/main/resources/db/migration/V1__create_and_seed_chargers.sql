CREATE TABLE chargers (
                          id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                          name text NOT NULL,
                          location text NOT NULL
);

INSERT INTO chargers (name, location)
VALUES
    ('Ładowarka A', 'Kraków, ul. Zabłocie'),
    ('Ładowarka B', 'Kraków, ul. Długa'),
    ('Ładowarka C', 'Kraków, al. Pokoju');