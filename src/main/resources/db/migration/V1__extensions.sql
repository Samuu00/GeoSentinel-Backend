-- Solo PostGIS: gli UUID li genera Hibernate, e gen_random_uuid() (per il seed) è nativo da PostgreSQL 13
CREATE EXTENSION IF NOT EXISTS postgis;
