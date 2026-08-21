import type { ReactNode, TableHTMLAttributes } from 'react';

export type TableProps = Omit<TableHTMLAttributes<HTMLTableElement>, 'children'> & {
  readonly caption?: ReactNode;
  readonly headers: readonly ReactNode[];
  readonly rows: readonly (readonly ReactNode[])[];
  readonly wrapperClassName?: string;
};

export function Table({
  caption,
  className = '',
  headers,
  rows,
  wrapperClassName = '',
  ...props
}: TableProps) {
  return (
    <div className={`os-table-wrap ${wrapperClassName}`.trim()}>
      <table {...props} className={`os-table ${className}`.trim()}>
        {caption && <caption>{caption}</caption>}
        <thead>
          <tr>
            {headers.map((header, index) => <th key={index} scope="col">{header}</th>)}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, rowIndex) => (
            <tr key={rowIndex}>
              {row.map((cell, cellIndex) => <td key={cellIndex}>{cell}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
