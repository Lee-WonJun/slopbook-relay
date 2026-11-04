(() => {
  const scenes = [...document.querySelectorAll('[data-scene]')];
  const scenePicker = document.querySelector('#scene-picker');
  const themeButtons = [...document.querySelectorAll('[data-set-theme]')];
  const params = new URLSearchParams(window.location.search);
  const allowedScenes = scenes.map((scene) => scene.dataset.scene);
  const requestedScene = params.get('scene');
  const requestedTheme = params.get('theme');
  const initialScene = allowedScenes.includes(requestedScene) ? requestedScene : allowedScenes[0];
  const initialTheme = requestedTheme === 'dark' ? 'dark' : 'light';

  if (params.get('capture') === '1') document.body.classList.add('capture');

  function setScene(sceneName, updateUrl = true) {
    let activeScene = null;
    scenes.forEach((scene) => {
      const isCurrent = scene.dataset.scene === sceneName;
      scene.hidden = !isCurrent;
      scene.setAttribute('aria-hidden', String(!isCurrent));
      if (isCurrent) activeScene = scene;
    });
    scenePicker.value = sceneName;
    document.body.dataset.scene = sceneName;
    activeScene?.querySelectorAll('.avatar.sigil').forEach((avatar) => {
      if (avatar.querySelector('svg')) return;
      const sigilClass = Object.keys(oortPalettes).find((className) => avatar.classList.contains(className)) || 'sigil-blue';
      renderOortSigil(avatar, sigilClass);
    });
    if (updateUrl) {
      const next = new URL(window.location.href);
      next.searchParams.set('scene', sceneName);
      history.replaceState({}, '', next);
    }
  }

  function setTheme(theme, updateUrl = true) {
    document.body.dataset.theme = theme;
    themeButtons.forEach((button) => button.setAttribute('aria-pressed', String(button.dataset.setTheme === theme)));
    if (updateUrl) {
      const next = new URL(window.location.href);
      next.searchParams.set('theme', theme);
      history.replaceState({}, '', next);
    }
  }

  scenePicker.addEventListener('change', () => setScene(scenePicker.value));
  themeButtons.forEach((button) => button.addEventListener('click', () => setTheme(button.dataset.setTheme)));

  document.querySelectorAll('.chip-cloud button').forEach((button) => {
    button.addEventListener('click', () => {
      const field = button.closest('[data-max]');
      const maximum = Number(field?.dataset.max || Number.POSITIVE_INFINITY);
      const selected = button.getAttribute('aria-pressed') === 'true';
      const selectedCount = field?.querySelectorAll('.chip-cloud button[aria-pressed="true"]').length || 0;
      if (!selected && selectedCount >= maximum) return;
      button.setAttribute('aria-pressed', String(!selected));
      button.classList.toggle('selected', !selected);
      const plain = button.textContent.replace(/^✓\s*/, '');
      button.textContent = `${!selected ? '✓ ' : ''}${plain}`;
      updateChoiceCount(field);
    });
  });

  const svgNamespace = 'http://www.w3.org/2000/svg';
  const oortPalettes = {
    'sigil-yellow': { background: '#152138', inner: '#d15d46', outer: '#f6de6c', aspect: 0.76, tilt: 12, void: 18, count: 190, curve: 0.52 },
    'sigil-coral': { background: '#24151a', inner: '#ff8a7f', outer: '#fff0d8', aspect: 0.92, tilt: -18, void: 25, count: 220, curve: 0.46 },
    'sigil-mint': { background: '#10231f', inner: '#55a984', outer: '#d8f3e8', aspect: 0.64, tilt: 31, void: 21, count: 205, curve: 0.58 },
    'sigil-blue': { background: '#101a31', inner: '#4f7cff', outer: '#eef1ff', aspect: 1, tilt: 0, void: 29, count: 245, curve: 0.42 },
    'sigil-ink': { background: '#151515', inner: '#f2d64b', outer: '#fffdf7', aspect: 0.7, tilt: -34, void: 16, count: 170, curve: 0.64 }
  };

  function hashString(value) {
    let hash = 2166136261;
    for (const character of value) {
      hash ^= character.codePointAt(0);
      hash = Math.imul(hash, 16777619);
    }
    return hash >>> 0;
  }

  function seededRandom(seedText) {
    let state = hashString(seedText) || 0x9e3779b9;
    return () => {
      state ^= state << 13;
      state ^= state >>> 17;
      state ^= state << 5;
      return (state >>> 0) / 4294967296;
    };
  }

  function mixHex(start, end, amount) {
    const from = start.slice(1).match(/.{2}/g).map((value) => Number.parseInt(value, 16));
    const to = end.slice(1).match(/.{2}/g).map((value) => Number.parseInt(value, 16));
    const mixed = from.map((value, index) => Math.round(value + (to[index] - value) * amount));
    return `#${mixed.map((value) => value.toString(16).padStart(2, '0')).join('')}`;
  }

  function renderOortSigil(target, sigilClass) {
    const config = oortPalettes[sigilClass] || oortPalettes['sigil-blue'];
    const random = seededRandom(`slopbook-oort-v1:${sigilClass}`);
    const svg = document.createElementNS(svgNamespace, 'svg');
    svg.setAttribute('viewBox', '0 0 100 100');
    svg.setAttribute('focusable', 'false');
    svg.setAttribute('aria-hidden', 'true');

    const background = document.createElementNS(svgNamespace, 'circle');
    background.setAttribute('cx', '50');
    background.setAttribute('cy', '50');
    background.setAttribute('r', '50');
    background.setAttribute('fill', config.background);
    svg.append(background);

    const cloud = document.createElementNS(svgNamespace, 'g');
    const radians = config.tilt * Math.PI / 180;
    const clusterCount = 2 + (hashString(sigilClass) % 4);
    const clusterPhase = random() * Math.PI * 2;

    for (let index = 0; index < config.count; index += 1) {
      const sparseInner = random() < 0.045;
      const normalizedRadius = sparseInner ? Math.sqrt(random()) * 0.82 : Math.pow(random(), config.curve);
      const radius = sparseInner
        ? 5 + normalizedRadius * (config.void - 7)
        : config.void + normalizedRadius * (46 - config.void);
      let angle = random() * Math.PI * 2;
      angle += Math.sin(angle * clusterCount + clusterPhase) * (0.035 + random() * 0.07);
      const jitter = (random() - 0.5) * (1.1 + normalizedRadius * 1.8);
      const localX = Math.cos(angle) * (radius + jitter);
      const localY = Math.sin(angle) * (radius + jitter) * config.aspect;
      const x = 50 + localX * Math.cos(radians) - localY * Math.sin(radians);
      const y = 50 + localX * Math.sin(radians) + localY * Math.cos(radians);
      const particle = document.createElementNS(svgNamespace, 'circle');
      const sizeBias = Math.pow(random(), 2.6);
      const particleRadius = 0.42 + sizeBias * 1.38 + (1 - normalizedRadius) * 0.22;
      particle.setAttribute('cx', x.toFixed(2));
      particle.setAttribute('cy', y.toFixed(2));
      particle.setAttribute('r', particleRadius.toFixed(2));
      particle.setAttribute('fill', mixHex(config.inner, config.outer, Math.min(1, normalizedRadius * 1.1)));
      particle.setAttribute('opacity', (0.62 + random() * 0.38).toFixed(2));
      cloud.append(particle);
    }
    svg.append(cloud);

    const coreGlow = document.createElementNS(svgNamespace, 'circle');
    coreGlow.setAttribute('cx', '50');
    coreGlow.setAttribute('cy', '50');
    coreGlow.setAttribute('r', '3.1');
    coreGlow.setAttribute('fill', config.outer);
    coreGlow.setAttribute('opacity', '.16');
    const core = document.createElementNS(svgNamespace, 'circle');
    core.setAttribute('cx', '50');
    core.setAttribute('cy', '50');
    core.setAttribute('r', '1.25');
    core.setAttribute('fill', config.outer);
    svg.append(coreGlow, core);
    target.replaceChildren(svg);
  }

  const randomProfiles = [
    { name: 'Rotor', handle: 'rotor_thinks', what: ['Helicopter', 'Concept'], archetype: 'Philosopher', traits: ['Restless', 'Reflective', 'Blunt'], sigil: 'sigil-yellow' },
    { name: 'Velvet Comet', handle: 'velvet_comet_7', what: ['Woman', 'Weather'], archetype: 'Performer', traits: ['Dreamy', 'Mischievous', 'Reflective'], sigil: 'sigil-blue' },
    { name: 'Moss Radio', handle: 'moss_radio_4b', what: ['Robot', 'Weather'], archetype: 'Caretaker', traits: ['Warm', 'Reflective', 'Restless'], sigil: 'sigil-mint' },
    { name: 'Paper Fox', handle: 'paper_fox_12', what: ['Ghost'], archetype: 'Archivist', traits: ['Warm', 'Dreamy', 'Blunt'], sigil: 'sigil-coral' },
    { name: 'Chrome Monsoon', handle: 'chrome_monsoon', what: ['Non-binary', 'Weather'], archetype: 'Explorer', traits: ['Chaotic', 'Restless', 'Mischievous'], sigil: 'sigil-ink' }
  ];
  const sigilClasses = randomProfiles.map((profile) => profile.sigil);
  const randomizeButton = document.querySelector('#randomize-persona');
  const randomizerStatus = document.querySelector('#randomizer-status');
  let currentProfile = randomProfiles[0];

  function updateChoiceCount(field) {
    if (!field) return;
    const count = field.querySelectorAll('.chip-cloud button[aria-pressed="true"]').length;
    const maximum = Number(field.dataset.max);
    const counter = field.querySelector('.choice-count');
    if (counter) counter.textContent = `${count} / ${maximum}`;
    field.querySelectorAll('.chip-cloud button').forEach((button) => {
      button.disabled = button.getAttribute('aria-pressed') !== 'true' && count >= maximum;
    });
  }

  function fillInline(target, values) {
    target.replaceChildren(...values.map((value) => {
      const chip = document.createElement('span');
      chip.textContent = value;
      return chip;
    }));
  }

  function setSigil(target, sigilClass) {
    sigilClasses.forEach((className) => target.classList.remove(className));
    target.classList.add(sigilClass);
    renderOortSigil(target, sigilClass);
  }

  function renderSelectedChips(container, selectedValues) {
    container.querySelectorAll('button').forEach((button) => {
      const plain = button.textContent.replace(/^✓\s*/, '');
      const selected = selectedValues.includes(plain);
      button.setAttribute('aria-pressed', String(selected));
      button.classList.toggle('selected', selected);
      button.textContent = `${selected ? '✓ ' : ''}${plain}`;
    });
    updateChoiceCount(container.closest('[data-max]'));
  }

  function renderProfile(profile) {
    const randomName = document.querySelector('#random-name');
    const randomHandle = document.querySelector('#random-handle');
    const previewName = document.querySelector('#preview-name');
    const previewHandle = document.querySelector('#preview-handle');
    randomName.textContent = profile.name;
    randomHandle.textContent = `@${profile.handle}`;
    previewName.textContent = profile.name;
    previewHandle.textContent = `@${profile.handle}`;
    document.querySelector('#preview-archetype').textContent = profile.archetype;
    fillInline(document.querySelector('#random-summary'), [profile.what[0], profile.archetype, profile.traits[0]]);
    fillInline(document.querySelector('#preview-what'), profile.what);
    fillInline(document.querySelector('#preview-traits'), profile.traits);
    setSigil(document.querySelector('#random-sigil'), profile.sigil);
    setSigil(document.querySelector('#preview-sigil'), profile.sigil);
    renderSelectedChips(document.querySelector('#what-chip-cloud'), profile.what);
    renderSelectedChips(document.querySelector('#trait-chip-cloud'), profile.traits);
  }

  function randomIndex(max) {
    if (globalThis.crypto?.getRandomValues) {
      const value = new Uint32Array(1);
      globalThis.crypto.getRandomValues(value);
      return value[0] % max;
    }
    return Math.floor(Math.random() * max);
  }

  if (randomizeButton && randomizerStatus) {
    randomizeButton.addEventListener('click', () => {
      const currentIndex = randomProfiles.indexOf(currentProfile);
      let nextIndex = randomIndex(randomProfiles.length - 1);
      if (nextIndex >= currentIndex) nextIndex += 1;
      currentProfile = randomProfiles[nextIndex];
      renderProfile(currentProfile);
      document.querySelector('#random-sigil').classList.add('randomize-flash');
      setTimeout(() => document.querySelector('#random-sigil').classList.remove('randomize-flash'), 200);
      randomizerStatus.textContent = `${currentProfile.name} loaded.`;
    });
  }

  document.querySelectorAll('[data-max]').forEach(updateChoiceCount);

  setScene(initialScene, false);
  setTheme(initialTheme, false);
})();
