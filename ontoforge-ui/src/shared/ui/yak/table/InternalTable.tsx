import { useLayoutEffect, useRef, type CSSProperties, type ReactNode } from "react";

import { cn } from "../cn";
import { Empty } from "../empty";
import { Pagination } from "../pagination";
import { Spinner } from "../spinner";
import { useFilter } from "./hooks/useFilter";
import { usePagination } from "./hooks/usePagination";
import { useSelection } from "./hooks/useSelection";
import { useSorter } from "./hooks/useSorter";
import type {
  TableAlign,
  TableChangeAction,
  TableColumn,
  TableFilters,
  TablePaginationConfig,
  TablePaginationState,
  TableProps,
  TableSize,
  TableSorterResult,
} from "./interface";
import { getTableColumnKey, resolveTableRowKey } from "./utils";

const sizeClasses: Record<TableSize, { header: string; cell: string }> = {
  small: {
    header: "h-8 px-3 text-xs",
    cell: "px-3 py-1.5 text-xs",
  },
  medium: {
    header: "h-10 px-3 text-xs",
    cell: "px-3 py-2.5 text-[13px]",
  },
  large: {
    header: "h-12 px-4 text-[13px]",
    cell: "px-4 py-3 text-sm",
  },
};

const alignClasses: Record<TableAlign, string> = {
  left: "text-left",
  center: "text-center",
  right: "text-right",
};

const getColumnStyle = <RecordType extends object>(
  column: TableColumn<RecordType>,
): CSSProperties => ({
  width: column.width,
  minWidth: column.minWidth,
});

const toCssLength = (width: CSSProperties["width"]): string | undefined => {
  if (typeof width === "number") return `${width}px`;
  return typeof width === "string" ? width : undefined;
};

const getFixedOffset = <RecordType extends object>(
  columns: readonly TableColumn<RecordType>[],
  index: number,
  side: "left" | "right",
): CSSProperties["left"] => {
  const indexes =
    side === "left"
      ? Array.from({ length: index }, (_, offset) => offset)
      : Array.from({ length: columns.length - index - 1 }, (_, offset) => index + offset + 1);
  const widths = indexes
    .filter((columnIndex) => columns[columnIndex]?.fixed === side)
    .map((columnIndex) => toCssLength(columns[columnIndex]?.width))
    .filter((width): width is string => Boolean(width));
  if (widths.length === 0) return 0;
  if (widths.length === 1) return widths[0];
  return `calc(${widths.join(" + ")})`;
};

const getCellStyle = <RecordType extends object>(
  column: TableColumn<RecordType>,
  index: number,
  columns: readonly TableColumn<RecordType>[],
): CSSProperties => ({
  ...getColumnStyle(column),
  ...(column.fixed === "left" ? { left: getFixedOffset(columns, index, "left") } : {}),
  ...(column.fixed === "right" ? { right: getFixedOffset(columns, index, "right") } : {}),
});

const fixedEdgeShadow = <RecordType extends object>(
  columns: readonly TableColumn<RecordType>[],
  index: number,
): string | undefined => {
  const column = columns[index];
  if (column?.fixed === "left" && columns[index + 1]?.fixed !== "left") {
    return "shadow-[4px_0_8px_-6px_rgba(16,24,40,0.24)]";
  }
  if (column?.fixed === "right" && columns[index - 1]?.fixed !== "right") {
    return "shadow-[-4px_0_8px_-6px_rgba(16,24,40,0.24)]";
  }
  return undefined;
};

const getCellTitle = (content: ReactNode): string | undefined =>
  typeof content === "string" || typeof content === "number" ? String(content) : undefined;

export function InternalTable<RecordType extends object>({
  bordered = false,
  className,
  columns,
  dataSource = [],
  emptyText,
  footer,
  loading = false,
  onChange,
  onRow,
  pagination,
  rowHoverable = true,
  rowKey,
  rowSelection,
  scroll,
  size = "medium",
  sticky = false,
}: TableProps<RecordType>) {
  const viewportRef = useRef<HTMLDivElement>(null);
  const headerRef = useRef<HTMLTableSectionElement>(null);
  const loadingRef = useRef<HTMLDivElement>(null);
  const filtersRef = useRef<TableFilters>({});
  const sorterRef = useRef<TableSorterResult<RecordType>>({});
  const paginationStateRef = useRef<TablePaginationState | false>(false);
  const filterDataRef = useRef<
    (data: readonly RecordType[], filtersOverride?: TableFilters) => readonly RecordType[]
  >((data) => data);
  const sortDataRef = useRef<
    (
      data: readonly RecordType[],
      sorterOverride?: TableSorterResult<RecordType>,
    ) => readonly RecordType[]
  >((data) => data);

  useLayoutEffect(() => {
    if (!loading) return;

    const viewport = viewportRef.current;
    const header = headerRef.current;
    const mask = loadingRef.current;
    if (!viewport || !header || !mask) return;

    const updateLoadingBounds = () => {
      const viewportTop = viewport.getBoundingClientRect().top;
      // Measure a cell because sticky th elements can move independently of thead.
      const headerBottom = header.rows[0]?.cells[0]?.getBoundingClientRect().bottom ?? viewportTop;
      const top = Math.min(viewport.clientHeight, Math.max(0, headerBottom - viewportTop));
      mask.style.top = `${top}px`;
      mask.style.height = `${Math.max(0, viewport.clientHeight - top)}px`;
      mask.style.width = `${viewport.clientWidth}px`;
    };

    updateLoadingBounds();
    const observer = new ResizeObserver(updateLoadingBounds);
    observer.observe(viewport);
    observer.observe(header);
    viewport.addEventListener("scroll", updateLoadingBounds, { passive: true });

    return () => {
      observer.disconnect();
      viewport.removeEventListener("scroll", updateLoadingBounds);
    };
  }, [loading, sticky]);

  const emitChange = (
    action: TableChangeAction,
    nextFilters: TableFilters,
    nextSorter: TableSorterResult<RecordType>,
    paginationOverride?: Pick<TablePaginationState, "current" | "pageSize">,
  ) => {
    if (!onChange) return;

    const currentDataSource = sortDataRef.current(
      filterDataRef.current(dataSource, nextFilters),
      nextSorter,
    );

    let nextPagination = paginationStateRef.current;
    if (nextPagination !== false) {
      nextPagination = {
        ...nextPagination,
        current: paginationOverride?.current ?? nextPagination.current,
        pageSize: paginationOverride?.pageSize ?? nextPagination.pageSize,
        total:
          pagination !== false && pagination?.total != null
            ? pagination.total
            : currentDataSource.length,
      };
    }

    onChange(nextPagination, nextFilters, nextSorter, {
      action,
      currentDataSource,
    });
  };

  const sorterState = useSorter(columns, (nextSorter) => {
    emitChange("sort", filtersRef.current, nextSorter);
  });
  sorterRef.current = sorterState.sorter;
  sortDataRef.current = sorterState.sortData;

  const filterState = useFilter(sorterState.columns, (nextFilters) => {
    emitChange("filter", nextFilters, sorterRef.current);
  });
  filtersRef.current = filterState.filters;
  filterDataRef.current = filterState.filterData;

  const processedData = sorterState.sortData(filterState.filterData(dataSource));

  const mergedPagination: false | TablePaginationConfig | undefined =
    pagination === false || pagination == null
      ? pagination
      : {
          ...pagination,
          onChange: (page, pageSize) => {
            pagination.onChange?.(page, pageSize);
            emitChange("paginate", filtersRef.current, sorterRef.current, {
              current: page,
              pageSize,
            });
          },
        };

  const { data, pagination: resolvedPagination } = usePagination(mergedPagination, processedData);
  paginationStateRef.current =
    resolvedPagination == null
      ? false
      : {
          current: resolvedPagination.page,
          pageSize: resolvedPagination.pageSize,
          total: resolvedPagination.total,
        };

  const { columns: mergedColumns, isSelected } = useSelection(
    rowSelection,
    data,
    filterState.columns,
    rowKey,
  );
  const tableLayoutFixed = mergedColumns.some((column) => column.ellipsis);
  const scrollStyle: CSSProperties | undefined =
    scroll?.y == null ? undefined : { maxHeight: scroll.y };
  const tableStyle: CSSProperties =
    scroll?.x === true
      ? { minWidth: "max-content" }
      : scroll?.x == null
        ? {}
        : { minWidth: scroll.x };
  const sizeClass = sizeClasses[size];

  return (
    <div className={cn("flex min-w-0 flex-col", className)}>
      <div className="relative min-h-0 shrink-0">
        <div
          ref={viewportRef}
          className={cn(
            "min-h-0 overflow-x-auto bg-[var(--yak-components-table-bg)]",
            scroll?.y != null && "overflow-y-auto",
          )}
          style={scrollStyle}
        >
          <table
            aria-busy={loading || undefined}
            className={cn(
              "w-full text-[var(--yak-components-table-text)]",
              bordered ? "border-separate border-spacing-0" : "border-collapse",
              tableLayoutFixed ? "table-fixed" : "table-auto",
            )}
            style={tableStyle}
          >
            <colgroup>
              {mergedColumns.map((column, index) => (
                <col key={getTableColumnKey(column, index)} style={getColumnStyle(column)} />
              ))}
            </colgroup>

            <thead ref={headerRef}>
              <tr>
                {mergedColumns.map((column, index) => {
                  const align = column.align ?? "left";
                  const fixed = column.fixed != null;

                  return (
                    <th
                      key={getTableColumnKey(column, index)}
                      scope="col"
                      style={getCellStyle(column, index, mergedColumns)}
                      className={cn(
                        "bg-[var(--yak-components-table-header-bg)] font-medium text-[var(--yak-components-table-header-text)]",
                        sizeClass.header,
                        alignClasses[align],
                        (sticky || fixed) && "sticky",
                        sticky && "top-0",
                        fixed ? "z-30" : sticky && "z-20",
                        scroll?.x != null && fixedEdgeShadow(mergedColumns, index),
                      )}
                    >
                      <div className={cn("min-w-0", column.ellipsis && "truncate")}>
                        {column.title}
                      </div>
                    </th>
                  );
                })}
              </tr>
            </thead>

            <tbody>
              {data.map((record, rowIndex) => {
                const rowProps = onRow?.(record, rowIndex) ?? {};
                const { className: rowClassName, ...restRowProps } = rowProps;
                const selected = isSelected(record, rowIndex);

                return (
                  <tr
                    {...restRowProps}
                    key={resolveTableRowKey(record, rowIndex, rowKey)}
                    data-selected={selected ? "true" : undefined}
                    className={cn(
                      "bg-[var(--yak-components-table-row-bg)]",
                      "data-[selected=true]:bg-[var(--yak-color-hover)]",
                      rowHoverable && "transition-colors hover:bg-[var(--yak-color-hover)]",
                      rowClassName,
                    )}
                  >
                    {mergedColumns.map((column, columnIndex) => {
                      const value = column.dataIndex == null ? undefined : record[column.dataIndex];
                      const cell = column.render
                        ? column.render(value, record, rowIndex)
                        : (value as ReactNode);
                      const align = column.align ?? "left";
                      const fixed = column.fixed != null;

                      return (
                        <td
                          key={getTableColumnKey(column, columnIndex)}
                          style={getCellStyle(column, columnIndex, mergedColumns)}
                          className={cn(
                            "border-b border-[var(--yak-components-table-border)] align-middle",
                            sizeClass.cell,
                            alignClasses[align],
                            bordered && "border-r first:border-l",
                            bordered && rowIndex === 0 && "border-t",
                            fixed && "sticky z-10 bg-inherit",
                            scroll?.x != null && fixedEdgeShadow(mergedColumns, columnIndex),
                          )}
                        >
                          <div
                            className={cn("min-w-0", column.ellipsis && "truncate")}
                            title={column.ellipsis ? getCellTitle(cell) : undefined}
                          >
                            {cell ?? null}
                          </div>
                        </td>
                      );
                    })}
                  </tr>
                );
              })}

              {data.length === 0 ? (
                <tr>
                  <td
                    colSpan={Math.max(mergedColumns.length, 1)}
                    className={cn(
                      "border-b border-[var(--yak-components-table-border)]",
                      bordered && "border-x border-t",
                    )}
                  >
                    {loading ? (
                      <div aria-hidden="true" className="min-h-48" />
                    ) : (
                      <Empty className="min-h-48" description={emptyText ?? "No data"} />
                    )}
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>

        {loading ? (
          <div
            ref={loadingRef}
            className="absolute left-0 z-20 flex items-center justify-center overflow-hidden bg-[var(--yak-components-table-loading-bg)]"
          >
            <Spinner size="xlarge" label="Loading table" />
          </div>
        ) : null}
      </div>

      {footer || resolvedPagination ? (
        <div className="mt-3 flex min-h-12 shrink-0 flex-nowrap items-center gap-4 px-4">
          {footer ? <div className="min-w-0 flex-1">{footer}</div> : null}
          {resolvedPagination ? (
            <Pagination
              className="ml-auto shrink-0"
              page={resolvedPagination.page}
              pageSize={resolvedPagination.pageSize}
              total={resolvedPagination.total}
              pageSizeOptions={resolvedPagination.pageSizeOptions}
              pageSizeLabel={resolvedPagination.pageSizeLabel}
              disabled={loading || resolvedPagination.disabled}
              showSizeChanger={resolvedPagination.showSizeChanger}
              showQuickJumper={resolvedPagination.showQuickJumper}
              renderTotal={resolvedPagination.renderTotal}
              onChange={resolvedPagination.onChange}
            />
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
