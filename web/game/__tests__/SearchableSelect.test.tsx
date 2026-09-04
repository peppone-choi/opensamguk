import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import SearchableSelect, { type SelectOption } from '@/components/command/SearchableSelect';

function option(value: number): SelectOption {
  return { value, label: `현 ${value}`, searchText: `익주 촉군 현 ${value} ${value}` };
}

describe('SearchableSelect large option sets', () => {
  it('shows only the default shortlist until the user searches', () => {
    const options = Array.from({ length: 40 }, (_, index) => option(index + 1));

    render(
      <SearchableSelect
        options={options}
        defaultOptions={options.slice(0, 3)}
        value={null}
        onChange={vi.fn()}
      />,
    );

    expect(screen.getAllByRole('option')).toHaveLength(3);
    expect(screen.queryByRole('option', { name: '현 40' })).not.toBeInTheDocument();

    fireEvent.change(screen.getByRole('textbox'), { target: { value: '40' } });

    expect(screen.getByRole('option', { name: '현 40' })).toBeInTheDocument();
  });

  it('caps broad search results and tells the user how many matches exist', () => {
    const options = Array.from({ length: 40 }, (_, index) => option(index + 1));

    render(
      <SearchableSelect
        options={options}
        defaultOptions={[]}
        resultLimit={30}
        value={null}
        onChange={vi.fn()}
      />,
    );

    fireEvent.change(screen.getByRole('textbox'), { target: { value: '현' } });

    expect(screen.getAllByRole('option')).toHaveLength(30);
    expect(screen.getByText('40개 중 30개 표시')).toBeInTheDocument();
  });
});
