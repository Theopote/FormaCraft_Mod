from types import SimpleNamespace as NS

import pytest

from app.services import planning_selection_bounds as bounds


def selection(minimum, maximum):
    return NS(min=NS(**dict(zip("xyz", minimum))), max=NS(**dict(zip("xyz", maximum))))


def request(sel=None, brush=None):
    return NS(selection=sel, brushSelection=brush, player=NS(pos=NS(x=100, y=64, z=200)))


def test_inclusive_reversed_bounds_and_selection_priority():
    sel = selection((110, 70, 220), (100, 64, 200))
    brush = selection((0, 0, 0), (1, 1, 1))
    req = request(sel, brush)
    assert bounds._selection_dimensions(req) == {"width": 11, "height": 7, "depth": 21}
    assert bounds._selection_dimensions(request(brush=brush)) == {"width": 2, "height": 2, "depth": 2}
    assert bounds._selection_bounds_relative(req, None) == {
        "min_x": 0, "max_x": 10, "min_y": 0, "max_y": 6, "min_z": 0, "max_z": 20}
    assert bounds._selection_bounds_relative(req, {"x": 105, "y": 66, "z": 210}) == {
        "min_x": -5, "max_x": 5, "min_y": -2, "max_y": 4, "min_z": -10, "max_z": 10}


@pytest.mark.parametrize("anchor_mode", ["min_corner", "center"])
@pytest.mark.parametrize("size", [1, 2, 3, 4, 9])
@pytest.mark.parametrize("position", [-100, 100])
def test_clipped_geometry_stays_inside_inclusive_bounds(anchor_mode, size, position):
    limits = {"min_x": -4, "max_x": 4, "min_y": 0, "max_y": 8, "min_z": -4, "max_z": 4}
    dims = {"width": size, "height": size, "depth": size}
    rp = {"x": position, "y": position, "z": position}
    assert bounds._clamp_relative_position(rp, dims, anchor_mode, limits) is rp
    half = 0 if anchor_mode == "min_corner" else size // 2
    for axis in "xz":
        minimum = rp[axis] - half
        assert -4 <= minimum <= minimum + size - 1 <= 4
    assert 0 <= rp["y"] <= rp["y"] + size - 1 <= 8


def test_dimensions_clip_before_position_and_no_selection_preserves_identity():
    dims = {"width": 20, "height": 30, "depth": 40}
    assert bounds._clamp_dimensions_to_selection(dims, None) is dims
    clipped = bounds._clamp_dimensions_to_selection(dims, {"width": 3, "height": 4, "depth": 5})
    assert clipped == {"width": 3, "height": 4, "depth": 5}
    assert dims == {"width": 20, "height": 30, "depth": 40}
    rp = {"x": 9, "y": 8, "z": 7}
    assert bounds._clamp_relative_position(rp, clipped, "center", None) is rp
    assert bounds._selection_dimensions(None) is None
    assert bounds._selection_bounds_relative(request(), None) is None
