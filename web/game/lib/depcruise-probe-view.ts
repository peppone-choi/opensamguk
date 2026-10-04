// Temporary red probe for tools/ci/depcruise_counts.py — a view model importing an api client. Reverted in the next commit.
import { fetchGame } from './api';

export const depcruiseProbe = fetchGame;
