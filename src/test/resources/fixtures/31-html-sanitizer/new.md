# Sanitizer

Safe paragraph.

<div onclick="steal()" style="color: red">Click <a href="javascript:alert(1)">here</a>.<script>alert(2)</script></div>

<div>
<img src="x.png" onerror="alert(3)"><img/onerror=alert(4) src=y.png>
</div>

Inline <a href="jav&#x61;script:alert(5)" title="t">link</a> and <span onmouseover='alert(6)'>span</span>.

<iframe src="https://example.com"></iframe>
<a href="https://example.com/ok">kept</a>
