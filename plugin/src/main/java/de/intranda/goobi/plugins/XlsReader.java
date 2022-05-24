package de.intranda.goobi.plugins;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import de.intranda.goobi.plugins.HuImporterWorkflowPlugin.MappingField;
import lombok.Getter;

public class XlsReader {
    @Getter
    private Sheet sheet;
    public XlsReader(String path) throws IOException {
        
        FileInputStream inputStream = new FileInputStream(new File(path));
        Workbook workbook = new XSSFWorkbook(inputStream);
        this.sheet = workbook.getSheetAt(0);
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
                    } else {
                        //in case someone wants to use whitespace as seperator
                        if (imf.getSeparator().length() > 0)
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
     * @param cellname
     * @return
     */
    public static String getCellContentSplit(Row row, String cellname) {
        Cell cell = row.getCell(CellReference.convertColStringToIndex(cellname));
        if (cell != null) {
            DataFormatter dataFormatter = new DataFormatter();
            return dataFormatter.formatCellValue(cell).trim();
        }
        return null;
    }
}
