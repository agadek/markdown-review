# 04-block-moved

Moved block (content unchanged).

- Section `## Which IDEs are supported?` + paragraph moved from the end to the top.
- Plain LCS: two blocks removed at the end on the left and two added at the top on the right; the rest equal.
- Moved-block detection (P2.7, FR-15): the statuses stay `added` and `removed`, and each pair with equal keys is
  linked as a move. The added end carries `data-moved-from="<idx>"` and the removed end `data-moved-to="<idx>"`,
  pointing at the other end's `data-idx`, and both show an `mdr-moved-link` label. No word diff.
- No block should be `modified`.
- Reverting a moved block moves it back (a delete and an insert in one command), so two clicks restore the left side.
- Not a move: a block whose text also changed, or a copy added while the original stays in place.
