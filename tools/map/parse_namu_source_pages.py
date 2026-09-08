#!/usr/bin/env python3
"""Extract attributed facts from supplied Namu page text, without historical adjudication.

Only explicit location fields/short coordinate-labelled lines become locations.
Coordinates in narrative remain separate mentions. No county inherits another
named site's coordinates. Source prose is not reproduced in the derived ledger.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
from pathlib import Path

HAN = r'\u3400-\u9fff\uf900-\ufaff\U00020000-\U0003134f'
GLOSS = re.compile(r'([가-힣]{1,24})\(([' + HAN + r']+)\)')
GEO_GLOSS = re.compile(r'([가-힣]{1,24})\(([' + HAN + r'·ㆍ/]+)\)')
HAN_NAMES = re.compile(r'\(([' + HAN + r'·ㆍ/]+)\)')
SECTION = re.compile(r'^(\d+(?:\.\d+)*)\.\s*(.+?)\[편집\]\s*$')
NAMED = re.compile(r'^([가-힣][가-힣\w·ㆍ -]{0,35}?)\(([' + HAN + r'·ㆍ/]+)\)(.*)$')
PAIR = re.compile(r'《\s*([+-]?\d+(?:\.\d+)?)(?:\s*,\s*|\s+)([+-]?\d+(?:\.\d+)?)\s*[》\]]')
UNKNOWN = re.compile(r'미상|불명|알 수 없|확인되지 않|확인할 수 없|어디인지')
UNCERTAIN = re.compile(r'\?|추정|추측|비정|설\b|일대|부근')
DISPUTED = re.compile(r'잘못된 비정|잘못.*판단|충돌|이설|논란|여러 설|견해|유력')
GEOGRAPHIC_SUFFIXES = tuple('城關津渡山嶺阪坂谷水江河湖澤洲原亭聚宮祠廟林塢壁道')
LABELS = ('소속', '지명', '위치')
TEMPORAL_LABEL = re.compile(r'(?:~?(?:기원전)?\d{1,4}\??(?:~\d{1,4}\??)?~?|(?:전한|후한|서진|삼국|한|위)(?:\s*(?:이전|이후))?)')


def han_names(value: str) -> list[str]:
    return list(dict.fromkeys(name for group in HAN_NAMES.findall(value)
                              for name in re.split('[·ㆍ/]', group)))


def timeline(value: str) -> list[dict]:
    result = []
    for part in value.split('→'):
        match = re.match(r'\s*\[([^]]+)\](.*)', part)
        result.append({'marker': match[1] if match else None,
                       'text': match[2].strip() if match else part.strip()})
    return result


def fact(line: int, text: str) -> dict:
    return {'line': line, 'raw': text, 'namesHan': han_names(text), 'timeline': timeline(text)}


def coordinate_pairs(text: str, line: int, diagnostics: list[dict]) -> list[dict]:
    pairs = []
    for match in PAIR.finditer(text):
        if ',' not in match[0] or not match[0].endswith('》'):
            diagnostics.append({'line': line, 'code': 'NONSTANDARD_COORDINATE_NOTATION'})
        lat, lon = float(match[1]), float(match[2])
        if -90 <= lat <= 90 and -180 <= lon <= 180 and math.isfinite(lat) and math.isfinite(lon):
            pairs.append({'lat': lat, 'lon': lon})
        else:
            diagnostics.append({'line': line, 'code': 'INVALID_COORDINATE'})
    if re.search(r'《\s*[+-]?\d', text) and not PAIR.search(text):
        diagnostics.append({'line': line, 'code': 'UNPARSED_COORDINATE_NOTATION'})
    return pairs


def named_heading(text: str):
    match = NAMED.match(text)
    if not match:
        return None
    tail = match[3].strip()
    # A sentence beginning with a place name is not a heading.
    if tail and not tail.startswith((':', '?', '·', 'ㆍ', '[', '(', '：')):
        return None
    return match[1].strip(), han_names(text.split(':', 1)[0]), tail


def parse_page(text: str, *, title: str, source_id: str) -> dict:
    lines = text.splitlines()
    sections, records, mentions, diagnostics = [], [], [], []
    named_mentions = []
    current = None
    section = None
    county = None
    mode = None
    pending = None
    body_started = False
    glosses = {}

    def close_record(end):
        if current is not None:
            current['lineEnd'] = end

    for number, raw in enumerate(lines, 1):
        value = raw.strip()
        section_match = SECTION.match(value)
        if section_match:
            body_started = True
            close_record(number - 1)
            section_title = section_match[2]
            name = NAMED.match(section_title)
            section = {'sectionId': f'{source_id}:section:{number}', 'sourceId': source_id,
                       'number': section_match[1], 'title': section_title, 'line': number,
                       'nameKo': name[1] if name else section_title,
                       'namesHan': han_names(section_title), 'aliases': [], 'affiliations': []}
            sections.append(section)
            current = county = None
            mode = pending = None
            continue
        if not body_started:
            continue
        # Namu footnotes and page chrome follow the final source section.
        if re.match(r'^\[\d+\]\s', value):
            close_record(number - 1)
            current = county = None
            mode = pending = None
            body_started = False
            continue
        heading = named_heading(value)
        if heading:
            close_record(number - 1)
            name_ko, names, tail = heading
            is_county = name_ko.endswith(('현', '국', '도')) and any(n.endswith(('縣', '县', '國', '国', '道')) for n in names)
            kind = 'COUNTY_HEADING' if is_county else 'OTHER_NAMED_SITE'
            current = {'recordId': f'{source_id}:{number}', 'sourceId': source_id,
                       'lineStart': number, 'lineEnd': number, 'kind': kind,
                       'sectionId': section['sectionId'] if section else None,
                       'section': {k: section[k] for k in ('number', 'title', 'nameKo', 'namesHan', 'line')} if section else None,
                       'nameKo': name_ko, 'namesHan': names,
                       'headingNote': tail.split('《', 1)[0].strip(':： '),
                       'aliases': [], 'affiliations': [], 'locations': [], 'flags': []}
            if is_county:
                county = current
            elif county:
                current['parentCountyId'] = county['recordId']
            records.append(current)
            mode = pending = None
            if '?' in tail:
                current['flags'].append('UNCERTAIN')
            if '《' in value or UNKNOWN.search(tail):
                pairs = coordinate_pairs(value, number, diagnostics)
                current['locations'].append({'line': number, 'label': 'HEADING', 'coordinatePairs': pairs,
                                             'unknown': bool(UNKNOWN.search(tail)), 'uncertain': bool(UNCERTAIN.search(tail))})
                if pairs:
                    mentions.append({'line': number, 'coordinatePairs': pairs, 'recordId': current['recordId'],
                                     'scope': 'COUNTY_LOCATION' if is_county else 'OTHER_SITE_LOCATION'})
            continue
        target = current if current is not None else section
        if target is None:
            continue
        target_id = target.get('recordId', target.get('sectionId'))
        glosses.setdefault(target_id, []).extend(
            {'nameKo': match[1], 'nameHan': match[2], 'line': number}
            for match in GLOSS.finditer(value))
        if current:
            if DISPUTED.search(value) and 'DISPUTED' not in current['flags']:
                current['flags'].append('DISPUTED')
        label_match = re.match(r'^(소속|지명|위치)\s*[:：]\s*(.*)$', value)
        if value in LABELS:
            pending = value
            mode = value
            continue
        if label_match:
            label, content = label_match.groups()
            mode = label
            pending = None
        elif pending and value:
            label, content = pending, value
            pending = None
        else:
            label, content = None, value
        # These are references, not newly located sites. In particular, an alias
        # or narrative landmark never receives the enclosing county's point.
        seen_names = set()
        for match in GEO_GLOSS.finditer(value):
            for name_han in re.split('[·ㆍ/]', match[2]):
                if name_han.endswith(GEOGRAPHIC_SUFFIXES) and name_han not in seen_names:
                    seen_names.add(name_han)
                    named_mentions.append({'sourceId': source_id, 'line': number,
                                           'nameKo': match[1], 'namesHan': [name_han],
                                           'kind': 'GEOGRAPHIC_NAME_MENTION',
                                           'coordinateStatus': 'MENTION_NOT_LOCATION', 'locations': [],
                                           'parentRecordId': current['recordId'] if current else None,
                                           'sectionId': section['sectionId'],
                                           'relation': 'ALIAS_MENTION' if label == '지명' else 'NARRATIVE_MENTION'})
        if label in ('소속', '지명'):
            target['affiliations' if label == '소속' else 'aliases'].append(fact(number, content))
            continue
        pairs = coordinate_pairs(value, number, diagnostics) if '《' in value else []
        # Explicit fields or short time/candidate-labelled location lines only.
        location_line = label == '위치' or (mode == '위치' and re.match(r'^[^《]{0,60}[:：]\s*《', value))
        if current and location_line and label != '위치':
            location_label = value.split(':', 1)[0].strip()
            explicit_names = [current['nameKo']] + [a['raw'] for a in current['aliases']]
            own_name = any(re.search(r'(?<![가-힣])' + re.escape(location_label) + r'(?![가-힣])', name)
                           for name in explicit_names)
            temporal = bool(TEMPORAL_LABEL.fullmatch(location_label))
            if not own_name and not temporal:
                site = {'recordId': f'{source_id}:{number}', 'sourceId': source_id,
                        'lineStart': number, 'lineEnd': number, 'kind': 'OTHER_NAMED_SITE',
                        'sectionId': section['sectionId'], 'section': dict(current['section']),
                        'nameKo': location_label.rstrip('?'), 'namesHan': [], 'headingNote': '',
                        'aliases': [], 'affiliations': [], 'flags': ['UNCERTAIN'] if '?' in value else [],
                        'relation': 'LOCATION_LABEL_UNRESOLVED',
                        'locations': [{'line': number, 'label': location_label, 'coordinatePairs': pairs,
                                       'unknown': bool(UNKNOWN.search(value)), 'uncertain': bool(UNCERTAIN.search(value))}]}
                if county:
                    site['parentCountyId'] = county['recordId']
                records.append(site)
                if pairs:
                    mentions.append({'line': number, 'coordinatePairs': pairs, 'recordId': site['recordId'],
                                     'scope': 'OTHER_SITE_LOCATION'})
                continue
        if current and location_line:
            location = {'line': number, 'label': '위치' if label == '위치' else value.split(':', 1)[0],
                        'coordinatePairs': pairs, 'unknown': bool(UNKNOWN.search(content)),
                        'uncertain': bool(UNCERTAIN.search(content))}
            if not pairs and not location['unknown']:
                location['note'] = content[:360]
            current['locations'].append(location)
            if location['uncertain'] and 'UNCERTAIN' not in current['flags']:
                current['flags'].append('UNCERTAIN')
        if pairs:
            mentions.append({'line': number, 'coordinatePairs': pairs,
                             'recordId': current['recordId'] if current else None,
                             'scope': ('COUNTY_LOCATION' if current['kind'] == 'COUNTY_HEADING' else 'OTHER_SITE_LOCATION')
                             if current and location_line else 'UNASSIGNED_PROSE'})
        if value in ('봉작', '치소', '신설현', '폐지현', '영현'):
            mode = pending = None
    close_record(len(lines))
    for item in sections + records:
        item_id = item.get('recordId', item.get('sectionId'))
        for alias in item['aliases']:
            attributions = [g for g in glosses.get(item_id, [])
                            if re.search(r'(?<![가-힣])' + re.escape(g['nameKo']) + r'(?![가-힣])', alias['raw'])]
            alias['nameAttributions'] = attributions
            alias['namesHan'] = list(dict.fromkeys(alias['namesHan'] + [g['nameHan'] for g in attributions]))
    for record in records:
        record.pop('section', None)
        points = {(p['lat'], p['lon']) for loc in record['locations'] for p in loc['coordinatePairs']}
        locations = record['locations']
        record['coordinateStatus'] = ('MULTIPLE' if len(points) > 1 else 'EXPLICIT' if points else
                                      'UNKNOWN' if any(loc['unknown'] for loc in locations) else
                                      'UNPARSED' if locations else 'MISSING')
        record['flags'].sort()
    return {'sections': sections, 'records': records, 'coordinateMentions': mentions,
            'namedMentions': named_mentions, 'diagnostics': diagnostics}


def parse_manifest(manifest: dict) -> dict:
    sources, sections, records, mentions, diagnostics = [], [], [], [], []
    named_mentions = []
    unique = {}
    for entry in manifest['files']:
        data = Path(entry['path']).read_bytes()
        sha = hashlib.sha256(data).hexdigest()
        if sha != entry['sha256']:
            raise ValueError(f'source SHA256 mismatch: {entry["title"]}')
        if sha in unique:
            if unique[sha]['title'] != entry['title']:
                raise ValueError('identical bytes have conflicting source titles')
            unique[sha]['attachmentCount'] += 1
            continue
        text = data.decode('utf-8')
        last_modified = re.search(r'^최근 수정 시각:\s*(.+)$', text, re.MULTILINE)
        source = {'sourceId': sha, 'title': entry['title'], 'sha256': sha,
                  'sourceUrl': 'https://namu.wiki/w/' + entry['title'],
                  'sourceLastModified': last_modified[1].strip() if last_modified else None,
                  'lineCount': len(text.splitlines()), 'attachmentCount': 1}
        unique[sha] = source
        sources.append(source)
        page = parse_page(text, title=entry['title'], source_id=sha)
        sections.extend(page['sections'])
        records.extend(page['records'])
        named_mentions.extend(page['namedMentions'])
        mentions.extend({'sourceId': sha, **item} for item in page['coordinateMentions'])
        diagnostics.extend({'sourceId': sha, **item} for item in page['diagnostics'])
    return {'schemaVersion': 1, 'ledgerId': 'namu-supplied-source-records-v1',
            'scope': 'SUPPLIED_TEXT_FACTS_NOT_HISTORICAL_VERIFICATION',
            'sources': sources, 'sections': sections, 'records': records,
            'coordinateMentions': mentions, 'namedMentions': named_mentions, 'diagnostics': diagnostics}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = parse_manifest(json.loads(args.manifest.read_text(encoding='utf-8')))
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'sources': len(result['sources']), 'records': len(result['records']),
                      'diagnostics': len(result['diagnostics'])}))


if __name__ == '__main__':
    main()
