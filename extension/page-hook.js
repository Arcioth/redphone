(() => {
  if (window.__redphoneMicHooked) return;
  window.__redphoneMicHooked = true;

  const activeTracks = new Set();

  function emitState() {
    window.dispatchEvent(
      new CustomEvent("redphone-mic-state", {
        detail: { active: activeTracks.size > 0, count: activeTracks.size },
      })
    );
  }

  function trackStream(stream) {
    if (!stream || typeof stream.getAudioTracks !== "function") return;
    for (const track of stream.getAudioTracks()) {
      if (track.readyState === "ended") continue;
      activeTracks.add(track);
      track.addEventListener("ended", () => {
        activeTracks.delete(track);
        emitState();
      });
      const origStop = track.stop.bind(track);
      track.stop = () => {
        activeTracks.delete(track);
        emitState();
        return origStop();
      };
    }
    emitState();
  }

  if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
    const origGUM = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
    navigator.mediaDevices.getUserMedia = async function (constraints) {
      const stream = await origGUM(constraints);
      if (constraints && constraints.audio) {
        trackStream(stream);
      }
      return stream;
    };
  }
})();
