# 26-relative-images

Relative image paths resolve against the directory of each side's file.

- With `leftBaseUri` / `rightBaseUri` set to this directory, `img/logo.svg` renders as `file:///.../26-relative-images/img/logo.svg` on both sides, so the image next to the compared file shows. `img/logo.svg` exists in this fixture.
- Image `Logo`: modified (title added); the `src` is equal, so it is not marked as a URL change.
- Image `Remote`: equal; an absolute `https://` URL is never rewritten (the page's CSP keeps remote images blocked, NFR-8).
- Last paragraph: modified, inline `revised` added.
- Without base URIs the paths stay as written.
