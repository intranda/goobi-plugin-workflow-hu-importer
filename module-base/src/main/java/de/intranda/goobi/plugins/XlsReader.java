package de.intranda.goobi.plugins;

import java.io.FileInputStream;
import java.io.IOException;

import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import de.intranda.goobi.plugins.HuImporterWorkflowPlugin.MappingField;
import lombok.Getter;

public class XlsReader {

    private Workbook workbook;
    @Getter
    private Sheet sheet;

    public XlsReader(String path) throws IOException {

        try (FileInputStream inputStream = new FileInputStream(path)) {
            this.workbook = new XSSFWorkbook(inputStream);
            this.sheet = this.workbook.getSheetAt(0);
        }
    }

    public void closeWorkbook() throws IOException {
        this.workbook.close();
    }

    /**
     * Read content vom excel cell
     * 
     * @param row
     * @param cellname
     * @return
     */
    public static String getCellContent(Row row, MappingField imf) {
        String[] cells = imf.getColumn().split(",");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            String readCell = getCellContentSplit(row, cells[i]);
            if (StringUtils.isNotBlank(readCell)) {
                if (i == 0) {
                    result.append(getCellContentSplit(row, cells[i]));
                } else {
                    //first add whitspace and/or separator
                    if (StringUtils.isNotBlank(imf.getSeparator())) {
                        if (imf.isBlankBeforeSeparator()) {
                            result.append(" ");
                        }
                        result.append(imf.getSeparator());
                        if (imf.isBlankAfterSeparator()) {
                            result.append(" ");
                        }
                    } else //in case someone wants to use whitespace as seperator
                    if (imf.getSeparator().length() > 0) {
                        result.append(imf.getSeparator());
                    }
                    //add content of Cell
                    result.append(getCellContentSplit(row, cells[i]));
                }
            }
        }
        return result.toString();
    }

    /**
     * Read content from excel cell as String
     * 
     * @param row
     * @param columnName
     * @return
     */
    public static String getCellContentSplit(Row row, String columnName) {
        Cell cell = row.getCell(CellReference.convertColStringToIndex(columnName));
        if (cell != null) {
            if (cell.getCellType() == CellType.FORMULA) {
                switch (cell.getCachedFormulaResultType()) {
                    case BOOLEAN:
                        return String.valueOf(cell.getBooleanCellValue());
                    case NUMERIC:
                        return String.valueOf(cell.getNumericCellValue());
                    case STRING:
                        return cell.getRichStringCellValue().toString();
                    default:
                        //TODO: Add Erromessage or throw Exception
                        return "Formula ResultType not supported yet";
                }
            } else {
                DataFormatter dataFormatter = new DataFormatter();
                return dataFormatter.formatCellValue(cell).trim();
            }
        }
        return null;
    }
}
