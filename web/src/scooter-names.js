// scooter-names.js — GET/PUT endpoints fuer den vom User vergebenen
// Anzeigename eines Scooters. Damit synct das Smartphone den Namen auf
// den Server und ein neues Geraet kann ihn nach erstem Connect wieder
// abholen — ohne Re-Tippen.
//
// Auth-Modell: der Endpoint akzeptiert sowohl INGEST_TOKEN (was das
// Smartphone fuers Streaming hat) als auch VIEW_TOKEN (was das Dashboard
// nutzt). Beide Tokens optional — wenn keiner gesetzt ist, ist der
// Endpoint offen (single-user Default wie bei /ws/ingest).

import { Router } from 'express';
import express from 'express';
import { getScooterName, upsertScooterName } from './db.js';
import { checkIngestAuth, checkViewAuth, ingestAuthEnabled, viewAuthEnabled } from './auth.js';

export const scooterNames = Router();

// Body-Parser nur lokal mounten — der Rest der App benutzt aktuell kein
// JSON-Body, also halten wir es ge-scoped.
scooterNames.use(express.json({ limit: '4kb' }));

function requireAnyToken(req, res, next) {
  // Wenn beide Auth-Sides offen sind, kein Auth-Check.
  if (!ingestAuthEnabled && !viewAuthEnabled) return next();
  if (checkIngestAuth(req) || checkViewAuth(req)) return next();
  res.status(401).json({ error: 'token_required' });
}

function isValidAddress(addr) {
  return typeof addr === 'string' && addr.trim().length > 0 && addr.length <= 64;
}

function isValidName(name) {
  return typeof name === 'string' && name.trim().length > 0 && name.length <= 128;
}

scooterNames.get('/', requireAnyToken, (req, res) => {
  const address = (req.query.address ?? '').toString().trim();
  if (!isValidAddress(address)) {
    res.status(400).json({ error: 'address_required' });
    return;
  }
  const row = getScooterName(address);
  if (!row) {
    res.status(404).json({ error: 'not_found' });
    return;
  }
  res.json(row);
});

scooterNames.put('/', requireAnyToken, (req, res) => {
  const body = req.body ?? {};
  const address = typeof body.address === 'string' ? body.address.trim() : '';
  const nameRaw = typeof body.name === 'string' ? body.name : '';
  const name = nameRaw.trim();
  if (!isValidAddress(address)) {
    res.status(400).json({ error: 'address_required' });
    return;
  }
  if (!isValidName(name)) {
    res.status(400).json({ error: 'name_required' });
    return;
  }
  const saved = upsertScooterName(address, name, Date.now());
  res.json(saved);
});
