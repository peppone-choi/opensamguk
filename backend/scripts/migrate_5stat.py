#!/usr/bin/env python3
"""
Migrate scenario JSON files from 3-stat to 5-stat general tuples.

Old tuple format (13-16 elements):
  [affinity, name, picture, nation, city,
   leadership, strength, intel,
   officerLevel, birthYear, deathYear,
   personality?, special?, motto?, politics?, charm?]

New tuple format (13-16 elements):
  [affinity, name, picture, nation, city,
   leadership, strength, intel, politics, charm,
   officerLevel, birthYear, deathYear,
   personality?, special?, motto?]

Stats are sourced from 삼국지14 무장정보.xlsx when available.
For unmatched generals, politics/charm are derived from the 3 stats.
"""

import json
import os
import glob
import sys
from collections import defaultdict

import openpyxl

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
XLSX_PATH = os.path.join(PROJECT_ROOT, '..', '삼국지14 무장정보.xlsx')
SCENARIO_DIR = os.path.join(PROJECT_ROOT, 'src', 'main', 'resources', 'data', 'scenarios')


def derive_politics(leadership, strength, intel):
    """Derive politics from 3 stats (mirrors ScenarioService.derivePolitics)."""
    base = int(intel * 0.5 + leadership * 0.3 + (100 - strength) * 0.2)
    return max(10, min(100, base))


def derive_charm(leadership, strength, intel, affinity):
    """Derive charm from 3 stats + affinity (mirrors ScenarioService.deriveCharm)."""
    base = int(leadership * 0.4 + intel * 0.3 + strength * 0.1 + 20)
    return max(10, min(100, base))


def load_xlsx_stats(xlsx_path):
    """Load 5-stat data from xlsx. Returns:
    - by_name_birth: dict of (name, birthYear) -> (lead, str, int, pol, charm)
    - by_name: dict of name -> (lead, str, int, pol, charm) for non-duplicate names
    """
    wb = openpyxl.load_workbook(xlsx_path, read_only=True)
    ws = wb['무장']

    name_entries = defaultdict(list)
    for row in ws.iter_rows(min_row=3, values_only=True):
        name = row[2]
        if not name:
            continue
        name = str(name)
        birth_year = int(row[4]) if row[4] is not None else None
        leadership = int(row[9]) if row[9] is not None else 50
        strength = int(row[10]) if row[10] is not None else 50
        intel = int(row[11]) if row[11] is not None else 50
        politics = int(row[12]) if row[12] is not None else 50
        charm = int(row[13]) if row[13] is not None else 50
        stats = (leadership, strength, intel, politics, charm)
        name_entries[name].append((birth_year, stats))

    wb.close()

    by_name_birth = {}
    by_name = {}
    for name, entries in name_entries.items():
        for birth_year, stats in entries:
            if birth_year is not None:
                by_name_birth[(name, birth_year)] = stats
        if len(entries) == 1:
            by_name[name] = entries[0][1]

    return by_name_birth, by_name


def migrate_general(old_tuple, by_name_birth, by_name):
    """Convert old-format general tuple to new 5-stat format."""
    # Extract old positions
    affinity = old_tuple[0]
    name = old_tuple[1]
    picture = old_tuple[2]
    nation = old_tuple[3]
    city = old_tuple[4]
    old_leadership = old_tuple[5]
    old_strength = old_tuple[6]
    old_intel = old_tuple[7]
    officer_level = old_tuple[8]
    birth_year = old_tuple[9]
    death_year = old_tuple[10]
    personality = old_tuple[11] if len(old_tuple) > 11 else None
    special = old_tuple[12] if len(old_tuple) > 12 else None
    motto = old_tuple[13] if len(old_tuple) > 13 else None

    # Look up 5-stat from xlsx
    name_str = str(name)
    birth_int = int(birth_year) if birth_year is not None else None
    stats = None

    if birth_int is not None:
        stats = by_name_birth.get((name_str, birth_int))
    if stats is None:
        stats = by_name.get(name_str)

    if stats:
        leadership, strength, intel, politics, charm = stats
    else:
        # Keep original stats, derive politics/charm
        leadership = int(old_leadership) if old_leadership is not None else 50
        strength = int(old_strength) if old_strength is not None else 50
        intel = int(old_intel) if old_intel is not None else 50

        # Check if old format already had explicit politics/charm
        old_politics = old_tuple[14] if len(old_tuple) > 14 else None
        old_charm = old_tuple[15] if len(old_tuple) > 15 else None

        aff_val = int(affinity) if affinity is not None else 0
        politics = int(old_politics) if old_politics is not None else derive_politics(leadership, strength, intel)
        charm = int(old_charm) if old_charm is not None else derive_charm(leadership, strength, intel, aff_val)

    # Build new tuple
    new_tuple = [affinity, name, picture, nation, city,
                 leadership, strength, intel, politics, charm,
                 officer_level, birth_year, death_year]

    # Append optional fields, trimming trailing nulls
    optionals = [personality, special, motto]
    # Find last non-null optional
    last_idx = -1
    for i, val in enumerate(optionals):
        if val is not None:
            last_idx = i
    if last_idx >= 0:
        new_tuple.extend(optionals[:last_idx + 1])

    return new_tuple


def migrate_scenario(filepath, by_name_birth, by_name, dry_run=False):
    """Migrate a single scenario file. Returns (matched, total) counts."""
    with open(filepath, 'r', encoding='utf-8') as f:
        data = json.load(f)

    matched = 0
    total = 0

    for key in ('general', 'general_ex'):
        generals = data.get(key, [])
        new_generals = []
        for g in generals:
            total += 1
            name_str = str(g[1])
            birth_int = int(g[9]) if len(g) > 9 and g[9] is not None else None
            was_matched = False
            if birth_int is not None and (name_str, birth_int) in by_name_birth:
                was_matched = True
            elif name_str in by_name:
                was_matched = True
            if was_matched:
                matched += 1
            new_generals.append(migrate_general(g, by_name_birth, by_name))
        data[key] = new_generals

    if not dry_run:
        with open(filepath, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
            f.write('\n')

    return matched, total


def main():
    dry_run = '--dry-run' in sys.argv

    print(f'Loading xlsx from: {XLSX_PATH}')
    by_name_birth, by_name = load_xlsx_stats(XLSX_PATH)
    print(f'  by (name,birth): {len(by_name_birth)} entries')
    print(f'  by name (unique): {len(by_name)} entries')

    scenario_files = sorted(glob.glob(os.path.join(SCENARIO_DIR, 'scenario_*.json')))
    print(f'\nMigrating {len(scenario_files)} scenario files{"  (DRY RUN)" if dry_run else ""}...')

    total_matched = 0
    total_generals = 0
    for filepath in scenario_files:
        filename = os.path.basename(filepath)
        matched, total = migrate_scenario(filepath, by_name_birth, by_name, dry_run)
        total_matched += matched
        total_generals += total
        if total > 0:
            print(f'  {filename}: {matched}/{total} matched from xlsx')

    print(f'\nDone. {total_matched}/{total_generals} generals matched from xlsx.')
    if dry_run:
        print('(No files were modified - dry run)')


if __name__ == '__main__':
    main()
