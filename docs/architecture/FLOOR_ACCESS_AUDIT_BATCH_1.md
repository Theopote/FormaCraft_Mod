# Floor access audit — batch 1

Component compilation now logs FloorAccessAudit after final post-processing and
existing stair occupancy/clearance validation. For a simple multi-floor main mass,
its slot offset and resolved envelope determine the expected floor elevations.
Captured stair cells are considered tread candidates only when the same flight
owns clearance one and two blocks above them. Final last-write block states must
retain the tread and both air cells. The report lists observed/missing floor levels.

This audit is nonblocking. Missing observations can mean stairs are absent, end
short of a floor, or use an unsupported layout. It never generates extra stairs.
The existing blocking stair conflict validator remains authoritative for removed
supports and blocked clearance. Diagnostics observe generated plan blocks before
world placement, not the actual world after landing/placement.

No complete connectivity claim is made: adjacent floor surfaces, intermediary
landings, doors, slab collision shapes and overlapping host ownership are not
pathfound. A disconnected tread can still yield an observed level. Candidate
ownership is spatial within the mass envelope, not a component host graph.
Complex nested masses and excessive floor counts are not assessed. Public hall
clearance and per-use semantic geometry remain separate future work.

Regression checks slot offset, two-floor observations, overwritten headroom,
removed top tread, absent flights and explicit connectivity=not_assessed.
