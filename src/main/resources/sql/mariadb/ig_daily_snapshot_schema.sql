-- MariaDB schema for Instant Gaming scraping snapshots (official API)

-- Stable per-game metadata. One row per game id.
CREATE TABLE IF NOT EXISTS game_meta (
    id              INT NOT NULL,

    name            VARCHAR(255) NOT NULL,
    type            VARCHAR(64)  NOT NULL,
    url             VARCHAR(512) NOT NULL,

    categories      JSON         NOT NULL,
    description     TEXT         NULL,

    topseller       BOOLEAN NOT NULL DEFAULT FALSE,
    preorder        BOOLEAN NOT NULL DEFAULT FALSE,
    giftcard        BOOLEAN NOT NULL DEFAULT FALSE,
    in_stock        BOOLEAN NOT NULL DEFAULT TRUE,
    steam_id        INT         NULL,

    PRIMARY KEY (id),
    INDEX idx_game_meta_name       (name),
    INDEX idx_game_meta_type       (type),
    INDEX idx_game_meta_flags      (preorder, giftcard, topseller, in_stock),
    FULLTEXT INDEX ft_game_meta_name_description (name, description)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Per-snapshot price point. One row per (snapshot_ts, id).
CREATE TABLE IF NOT EXISTS snapshot_prices (
    snapshot_ts     DATETIME(3) NOT NULL,
    id              INT         NOT NULL,

    price           DOUBLE      NOT NULL,
    retail          DOUBLE      NOT NULL,
    discount        INT         NOT NULL,
    abs_discount    DOUBLE AS (retail - price) STORED,

    PRIMARY KEY (snapshot_ts, id),
    INDEX idx_snapshot_prices_id                 (id),
    INDEX idx_snapshot_prices_ts_discount        (snapshot_ts, discount),
    INDEX idx_snapshot_prices_ts_abs_discount    (snapshot_ts, abs_discount),
    INDEX idx_snapshot_prices_ts_price           (snapshot_ts, price),
    INDEX idx_snapshot_prices_ts_retail          (snapshot_ts, retail),
    CONSTRAINT fk_snapshot_prices_game
        FOREIGN KEY (id) REFERENCES game_meta(id)
        ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Aggregated per-snapshot statistics.
CREATE TABLE IF NOT EXISTS snapshot_stats (
    snapshot_ts         DATETIME(3) NOT NULL,

    game_count          INT NOT NULL,

    avg_discount        DOUBLE NOT NULL,
    min_discount        INT NOT NULL,
    max_discount        INT NOT NULL,

    avg_abs_discount    DOUBLE NOT NULL,
    min_abs_discount    DOUBLE NOT NULL,
    max_abs_discount    DOUBLE NOT NULL,

    PRIMARY KEY (snapshot_ts)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
