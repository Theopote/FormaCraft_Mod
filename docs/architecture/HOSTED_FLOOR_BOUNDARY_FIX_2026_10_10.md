# Hosted interior floor boundary correction

The game log contained a MASS_SECONDARY plate at (0,5,0), sized 25x17,
hosted by the 25x17 main house. The main shell already kept its own floors
inside the walls; the later full-footprint plate repainted the exterior ring.

During component preparation, hosted plates are now intersected with the
host rectangle inset by its wall thickness. Existing partial plate footprints
are intersected, not expanded. Floor height normalization and material selection
remain in place. Independent platforms without a matching host are unchanged.
`exposed_floor_edges: true` preserves explicitly authored exposed edges.
This parameter is a geometry opt-in, not a newly implemented natural-language
parser for facade bands. Explicit decorative bands remain separate components.

The regression covers a two-block wall and the explicit exposed-edge opt-in.
The inset uses the host rectangular envelope; complex courtyard/curved/setback
floor masks require their separate geometry paths and are not claimed here.
