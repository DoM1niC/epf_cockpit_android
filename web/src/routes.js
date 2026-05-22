// routes.js — JSON API endpoints.

import { Router } from 'express';
import { listTrips, getTrip, getTripSamples, getTelemetryInRange } from './db.js';
import { getSnapshot } from './broadcast.js';

export const api = Router();

api.get('/state', (_req, res) => {
  res.json(getSnapshot());
});

/**
 * GET /api/telemetry?from=<ms>&to=<ms>&max=<N>
 * Range-Query auf historische Telemetry. Default: letzte 5 Min, max 600 Punkte.
 * Bei mehr Rohdaten als `max` macht der DB-Helper Bucket-Avg-Downsampling.
 */
api.get('/telemetry', (req, res) => {
  const to = Number.parseInt(req.query.to, 10) || Date.now();
  const from = Number.parseInt(req.query.from, 10) || (to - 5 * 60 * 1000);
  const max = Math.min(Math.max(Number.parseInt(req.query.max, 10) || 600, 10), 5000);
  if (from >= to) {
    res.status(400).json({ error: 'from_must_be_before_to' });
    return;
  }
  const samples = getTelemetryInRange(from, to, max);
  res.json({ from, to, count: samples.length, samples });
});

api.get('/trips', (req, res) => {
  const limit = Number.parseInt(req.query.limit, 10) || 100;
  res.json({ trips: listTrips(limit) });
});

api.get('/trips/:id', (req, res) => {
  const trip = getTrip(req.params.id);
  if (!trip) {
    res.status(404).json({ error: 'trip_not_found' });
    return;
  }
  res.json({
    trip,
    samples: getTripSamples(req.params.id)
  });
});
