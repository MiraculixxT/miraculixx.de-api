-- MariaDB schema for daily Instant Gaming scraping snapshots

CREATE TABLE IF NOT EXISTS snapshot_item (
    snapshot_ts     DATETIME(3) NOT NULL,
    prod_id         INT NOT NULL,

    name            VARCHAR(255) NOT NULL,
    platform        VARCHAR(64) NOT NULL,
    seo_name        VARCHAR(255) NOT NULL,

    is_sub          BOOLEAN NOT NULL,
    is_prepaid      BOOLEAN NOT NULL,
    is_dlc          BOOLEAN NOT NULL,
    preorder        BOOLEAN NOT NULL,
    has_stock       BOOLEAN NOT NULL,

    retail          DOUBLE NOT NULL,
    price           DOUBLE NOT NULL,
    discount        INT NOT NULL,
    abs_discount    DOUBLE AS (retail - price) STORED,

    PRIMARY KEY (snapshot_ts, prod_id),
    INDEX idx_snapshot_item_prod_id (prod_id),
    INDEX idx_snapshot_item_discount (snapshot_ts, discount),
    INDEX idx_snapshot_item_abs_discount (snapshot_ts, abs_discount)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

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

CREATE OR REPLACE VIEW snapshot_overview AS
SELECT
    s.snapshot_ts,
    s.game_count,
    s.avg_discount,
    s.min_discount,
    s.max_discount,
    s.avg_abs_discount,
    s.min_abs_discount,
    s.max_abs_discount
FROM snapshot_stats s
ORDER BY s.snapshot_ts DESC;

