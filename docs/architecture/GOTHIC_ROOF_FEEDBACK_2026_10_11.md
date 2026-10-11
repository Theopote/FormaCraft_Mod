# Gothic roof game feedback

Latest game logs show typology_structure_hit and typology_builder_hit followed by
completed placement. The failed roof originated in the native builder: addGableRoof
shrunk Z (the church length), wrote only end strips for a fixed number of steps,
and then a single floating ridge. Large middle areas were never emitted.

Roof generation now spans all longitudinal Z rows and slopes across nave width.
Each X/Z roof column is emitted, steep vertical differences are sealed, and end
gables are closed above roof base. Side aisles receive lower lean-to roofs instead
of remaining roofless. Side-wall glazing is emitted on outer enclosure surfaces;
clerestory glazing is only added above the side roof where height permits.

Final-state regression checks every nave and both aisle roof columns for three
widths and four orientations, alongside existing portal/rose/tower/opt-out checks.
This is an envelope coverage test, not watertight collision-shape certification.

The builder remains a rectangular basilican hall. It does not yet implement a
transept/crossing, choir/apse, ambulatory or vault system. Gothic churches admit
plan variation; a rectangular hall must not be advertised as complete coverage.
See academic teaching diagram: https://people.wou.edu/~ckaser10/myweb/plan.html
Next: explicit plan variants and composed transept/choir/apse with roof junctions,
user dimensions and floor connectivity validated as a complete building.
