import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, appendFileSync } from 'node:fs';

const args = process.argv.slice(2);
const option = name => {
  const index = args.indexOf(`--${name}`);
  return index < 0 ? undefined : args[index + 1];
};
const run = (program, values) => execFileSync(program, values, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
const git = (...values) => run('git', values);
const project = option('project');
const version = option('version');
const versionPattern = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/;
if (!['sounds', 'mru', 'shields', 'fog', 'glass'].includes(project) || !versionPattern.test(version ?? '')) throw new Error('Provide --project sounds|mru|shields|fog|glass and --version X.Y.Z. Optional: --from REF, --write.');
const suffix = version.match(/^[^-+]+(?:-([^+]+))?(?:\+(.+))?$/);
if ([suffix?.[1], suffix?.[2]].filter(Boolean).some(value => value.split('.').some(part => !part)) || suffix?.[1]?.split('.').some(part => /^0\d+$/.test(part))) throw new Error('Invalid semantic version suffix');
const head = git('rev-parse', 'HEAD');
const tag = `${project}/v${version}`;
if (git('tag', '--list', tag)) throw new Error(`Tag already exists: ${tag}`);
let base = option('from');
if (!base) {
  try { base = git('describe', '--tags', '--abbrev=0', '--first-parent', '--match', `${project}/v*`, head); } catch {}
}
if (!base) throw new Error(`No previous ${project} release tag. Supply --from with the explicit first-release baseline commit.`);
const baseline = git('rev-parse', '--verify', `${base}^{commit}`);
git('merge-base', '--is-ancestor', baseline, head);
const propertiesPath = `${project}/mod.properties`;
const properties = readFileSync(propertiesPath, 'utf8');
const current = properties.match(/^mod\.version=(.+)$/m)?.[1].trim();
if (!current) throw new Error('Missing mod.version');
if (current === version) throw new Error(`Version must differ from ${current}`);
const remote = git('remote', 'get-url', 'origin');
const repository = process.env.GITHUB_REPOSITORY ?? remote.match(/github\.com[:/](.+?)(?:\.git)?$/)?.[1];
if (!repository || !/^[\w.-]+\/[\w.-]+$/.test(repository)) throw new Error('Cannot determine GitHub repository');
const api = endpoint => JSON.parse(run('gh', ['api', '--paginate', '--slurp', `repos/${repository}/${endpoint}`])).flat();
const commits = new Set(git('rev-list', `${baseline}..${head}`).split('\n'));
const pulls = api('pulls?state=closed&base=glass%2F1.21.1&per_page=100')
  .filter(pr => pr.merged_at && commits.has(pr.merge_commit_sha) && pr.labels.some(label => label.name === `project:${project}`))
  .sort((a, b) => a.merged_at.localeCompare(b.merged_at) || a.number - b.number);
const categories = ['Added', 'Changed', 'Deprecated', 'Removed', 'Fixed', 'Security'];
const sections = new Map(categories.map(category => [category, []]));
const changelogPath = `${project}/CHANGELOG.md`;
const changelog = readFileSync(changelogPath, 'utf8').replace(/\r\n/g, '\n');
if (!changelog.startsWith('# Changelog\n')) throw new Error('Expected # Changelog heading');
if (changelog.split('\n').some(line => line.startsWith(`## [${version}]`))) throw new Error('Changelog already contains this version');
const history = changelog.slice('# Changelog'.length).trimStart();
const unreleasedHeading = history.match(/^## (?:\[Unreleased\]|Unreleased)\s*\n/);
const unreleasedBody = unreleasedHeading ? history.slice(unreleasedHeading[0].length).split(/^(?=## )/m)[0] : '';
const released = unreleasedHeading ? history.slice(unreleasedHeading[0].length + unreleasedBody.length) : history;
let unreleasedCategory;
for (const line of unreleasedBody.split('\n')) {
  if (!line.trim()) continue;
  const heading = line.match(/^### (\w+)\s*$/);
  if (heading) {
    unreleasedCategory = heading[1];
    if (!sections.has(unreleasedCategory)) throw new Error(`Unreleased: unknown category ${unreleasedCategory}`);
  } else if (unreleasedCategory && /^- \S/.test(line)) {
    sections.get(unreleasedCategory).push(line);
  } else if (unreleasedCategory && /^ {2,}\S/.test(line) && sections.get(unreleasedCategory).length) {
    const values = sections.get(unreleasedCategory);
    values[values.length - 1] += `\n${line}`;
  } else throw new Error('Unreleased: use ### category headings and Markdown bullet entries');
}
for (const pr of pulls) {
  const comments = api(`issues/${pr.number}/comments?per_page=100`);
  const marker = `[release-changelog-${project}]: #`;
  const matches = comments.filter(comment => comment.user.type === 'Bot' && comment.body.split(/\r?\n/).includes(marker));
  if (matches.length !== 1) throw new Error(`PR #${pr.number}: expected one ${project} changelog comment`);
  const body = matches[0].body.split(/\r?\n/).filter(line => line !== marker).join('\n').trim();
  if (body === 'No changelog needed.') continue;
  if (!body || /\bTODO\b/.test(body)) throw new Error(`PR #${pr.number}: unfinished changelog`);
  let category;
  let entries = 0;
  for (const line of body.split(/\r?\n/)) {
    if (!line.trim()) continue;
    const heading = line.match(/^### (\w+)\s*$/);
    if (heading) {
      category = heading[1];
      if (!sections.has(category)) throw new Error(`PR #${pr.number}: unknown category ${category}`);
    } else if (category && /^- \S/.test(line)) {
      sections.get(category).push(`${line} ([#${pr.number}](${pr.html_url}))`);
      entries++;
    } else if (category && /^ {2,}\S/.test(line) && sections.get(category).length) {
      const values = sections.get(category);
      values[values.length - 1] += `\n${line}`;
    } else throw new Error(`PR #${pr.number}: use ### category headings and Markdown bullet entries`);
  }
  if (!entries) throw new Error(`PR #${pr.number}: empty changelog`);
}
const notes = [...sections].filter(([, entries]) => entries.length).map(([category, entries]) => `### ${category}\n\n${entries.join('\n')}`).join('\n\n') || 'No changelog provided.';
const date = new Date().toISOString().slice(0, 10);
const updated = `# Changelog\n\n## [${version}] - ${date}\n\n${notes}\n\n${released}`;
console.log(`${project}: ${current} -> ${version}\nBase: ${base}\nCommit: ${head}\nPRs: ${pulls.map(pr => `#${pr.number}`).join(', ')}\n\n${notes}\n`);
if (args.includes('--write')) {
  writeFileSync(propertiesPath, properties.replace(/^mod\.version=.*$/m, `mod.version=${version}`));
  writeFileSync(changelogPath, updated);
  console.log(`Updated ${propertiesPath} and ${changelogPath}. No commits, tags, or uploads created.`);
}
if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `tag=${tag}\nhead=${head}\n`);
if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, `## ${project} ${version}\n\n${notes}\n`);
