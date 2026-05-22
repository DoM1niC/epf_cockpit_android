// routes.js — JSON API endpoints.

import { Router } from 'express';
import { listTrips, getTrip, getTripSamples } from './db.js';
import { getSnapshot } from './broadcast.js';

export const api = Router();

api.get('/state', (_req, res) => {
  res.json(getSnapshot());
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
