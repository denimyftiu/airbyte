with table_row_counts as (
    select distinct count(*) as row_count, 2 as expected_count
    from {{ source('test_normalization', '_airbyte_raw_nested_stream_with_complex_columns_resulting_into_long_names') }}
union all
    select distinct count(*) as row_count, 2 as expected_count
    from {{ ref('nested_stream_with_complex_columns_resulting_into_long_names') }}
union all
    select distinct count(*) as row_count, 2 as expected_count
    from {{ ref('nested_stream_with_complex_columns_resulting_into_long_names_partition') }}
union all
    select count(distinct currency) as row_count, 1 as expected_count
    from {{ ref('nested_stream_with_complex_columns_resulting_into_long_names_partition_data') }}
-- union all
--    select count(distinct id) as row_count, 3 as expected_count
--    from {{ ref('nested_stream_with_complex_columns_resulting_into_long_names_partition_double_array_data') }}
)
select *
from table_row_counts
where row_count != expected_count
