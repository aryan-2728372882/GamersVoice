document.addEventListener("DOMContentLoaded", () => {
  const canvas = document.getElementById("waveCanvas");
  if (!canvas) return;
  const ctx = canvas.getContext("2d");
  const slider = document.getElementById("noiseSlider");
  const label = document.getElementById("noisePercentLabel");
  let currentSuppression = Number(slider?.value || 75);
  let currentScenario = "fan";
  let phase = 0;
  let visible = true;

  function resize() {
    const r = canvas.getBoundingClientRect();
    canvas.width = Math.max(320, Math.floor(r.width * devicePixelRatio));
    canvas.height = Math.floor(220 * devicePixelRatio);
  }
  resize();
  window.addEventListener("resize", resize);

  slider?.addEventListener("input", (e) => {
    currentSuppression = parseInt(e.target.value, 10);
    if (!label) return;
    if (currentSuppression === 0) label.textContent = "0% · raw mic";
    else if (currentSuppression < 50) label.textContent = currentSuppression + "% · free gate";
    else if (currentSuppression < 100) label.textContent = currentSuppression + "% · VIP squad";
    else label.textContent = "100% · Lifetime studio";
  });

  document.querySelectorAll("[data-scenario]").forEach((btn) => {
    btn.addEventListener("click", () => {
      document.querySelectorAll("[data-scenario]").forEach((b) => b.classList.remove("active"));
      btn.classList.add("active");
      currentScenario = btn.getAttribute("data-scenario");
    });
  });

  function draw() {
    const width = canvas.width;
    const height = canvas.height;
    const midY = height / 2;
    ctx.clearRect(0, 0, width, height);
    phase += 0.05;
    const cleanRatio = currentSuppression / 100;
    const noiseRatio = 1 - cleanRatio;

    ctx.strokeStyle = "rgba(255,255,255,0.05)";
    for (let x = 0; x < width; x += 48) {
      ctx.beginPath();
      ctx.moveTo(x, 0);
      ctx.lineTo(x, height);
      ctx.stroke();
    }

    if (noiseRatio > 0.04) {
      ctx.beginPath();
      ctx.strokeStyle = "rgba(255,93,122," + (noiseRatio * 0.8) + ")";
      ctx.lineWidth = 2 * devicePixelRatio;
      for (let x = 0; x < width; x += 4) {
        let nAmp;
        if (currentScenario === "fan") nAmp = Math.sin(x * 0.18 + phase * 3) * 18 * noiseRatio + (Math.random() - 0.5) * 14 * noiseRatio;
        else if (currentScenario === "keyboard") nAmp = (Math.random() - 0.5) * 36 * noiseRatio;
        else nAmp = Math.sin(x * 0.04 + phase) * 22 * noiseRatio + (Math.random() - 0.5) * 16 * noiseRatio;
        const y = midY + nAmp * devicePixelRatio;
        if (x === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      }
      ctx.stroke();
    }

    ctx.beginPath();
    ctx.strokeStyle = cleanRatio > 0.8 ? "#f5c542" : "#3dffb0";
    ctx.lineWidth = 2.6 * devicePixelRatio;
    for (let x = 0; x < width; x += 2) {
      const vAmp = Math.sin(x * 0.02 + phase) * 40 * Math.sin(x * 0.005) + Math.cos(x * 0.04 - phase * 0.5) * 16;
      const y = midY + vAmp * devicePixelRatio * 0.55;
      if (x === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    }
    ctx.stroke();
    if (visible) requestAnimationFrame(draw);
  }

  if ("IntersectionObserver" in window) {
    const io = new IntersectionObserver((entries) => {
      visible = entries[0].isIntersecting;
      if (visible) requestAnimationFrame(draw);
    }, { threshold: 0.05 });
    io.observe(canvas);
  } else {
    requestAnimationFrame(draw);
  }
});
