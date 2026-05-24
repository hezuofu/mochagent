import React from 'react';
import { Button, Form } from 'antd';
import { SearchOutlined, ReloadOutlined } from '@ant-design/icons';

interface FilterBoxProps {
  /** 是否显示"查询"按钮 */
  hasSearch?: boolean;
  /** 是否显示"重置"按钮 */
  hasReset?: boolean;
  /** 查询回调 */
  onSearch?: () => void;
  /** 重置回调 */
  onReset?: () => void;
  /** 表单内容(Form.Item) */
  children?: React.ReactNode;
  /** 额外的操作区域(slot) */
  extra?: React.ReactNode;
}

const FilterBox: React.FC<FilterBoxProps> = ({
  hasSearch = true,
  hasReset = true,
  onSearch,
  onReset,
  children,
  extra,
}) => {
  return (
    <div className="filter-box">
      <Form
        layout="inline"
        labelAlign="right"
        style={{
          display: 'flex',
          flexWrap: 'wrap',
          alignItems: 'flex-start',
        }}
      >
        <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 0, flex: 1 }}>
          {children}
        </div>
        <div
          className="filter-box-actions"
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            marginBottom: 16,
            flexShrink: 0,
            paddingLeft: 8,
          }}
        >
          {hasSearch && (
            <Button
              type="primary"
              icon={<SearchOutlined />}
              onClick={onSearch}
            >
              查询
            </Button>
          )}
          {hasReset && (
            <Button icon={<ReloadOutlined />} onClick={onReset}>
              重置
            </Button>
          )}
          {extra && (
            <div style={{ float: 'right' }}>{extra}</div>
          )}
        </div>
      </Form>
    </div>
  );
};

export default FilterBox;
