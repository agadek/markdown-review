# 07-tables

Tables (diff by row, then by cell).

Table 1:
- Added column `Notes`: header row modified (added cell). Every row gets an added cell; if the cell is empty the row's content is equal, but the column structure changes. Expected render: column `Notes` marked as added.
- `IntelliJ IDEA`: equal (apart from the new empty cell).
- `PyCharm`: modified, cell `2025.1` -> `2025.2`.
- `WebStorm`: modified, `2024.3` -> `2025.1`, `Tested` -> `Supported`, added `JCEF required`.
- `Rider`: removed. `GoLand`: added (different keys in the first column, do not pair as modified).

Table 2:
- Only the alignment of column A changes (`:--` -> `:-:`): content equal. The change is visible in the render (alignment); either mark the table as modified with no word diff or ignore it. This is the engine's decision; the test should pin it down.
